package org.pgledger.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one handler of HTTP errors of the admin web. The handler of Spring renders the standard exceptions (and the
 * {@code ErrorResponseException} of the size check) as problem details; this class adds the business failures of
 * {@link LedgerException} (also the ones that the security filters hand over through the
 * {@code HandlerExceptionResolver}), the integrity violations and a last catch-all that logs the failure with a trace
 * id and answers with a plain sentence.
 *
 * <p>A browser that navigates (the request accepts {@code text/html}) gets the {@code error} page with the same
 * status; everything else gets the problem detail. The page can only be rendered inside the dispatcher: a failure that
 * a filter hands over before the dispatcher runs is always a problem detail.
 */
@RestControllerAdvice
public class AdminHttpExceptionAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdminHttpExceptionAdvice.class);

    private final AdminProblems problems;
    private final LocaleResolver locales;

    public AdminHttpExceptionAdvice(AdminProblems problems, LocaleResolver localeResolver) {
        this.problems = problems;
        this.locales = localeResolver;
    }

    /** Every text field of a form is trimmed, and a field that is left empty is absent (null), so that "required" means it. */
    @InitBinder
    void trimText(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ExceptionHandler(LedgerException.class)
    Object ledger(LedgerException failure, HttpServletRequest request) {
        ProblemDetail problem = problems.problem(failure, locales.resolveLocale(request));
        if (failure instanceof InfrastructureException infrastructure
                && infrastructure.code() != InfrastructureCode.DRIVER_INVALID
                && infrastructure.code() != InfrastructureCode.DRIVER_NOT_CONFIGURED) {
            String traceId = problems.newTraceId();
            problem.setProperty("traceId", traceId);
            LOG.error("the request failed with {} (trace {})", failure.code().token(), traceId, failure);
        } else {
            LOG.debug("the request was refused with {}", failure.code().token(), failure);
        }
        return render(problem, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    Object integrity(DataIntegrityViolationException failure, HttpServletRequest request) {
        return ledger(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of(), failure),
                request);
    }

    /** The boundary: anything unexpected is logged with its cause and answered without any internal detail. */
    @ExceptionHandler(Exception.class)
    Object unexpected(Exception failure, HttpServletRequest request) {
        return ledger(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), failure),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", codeOf(status));
        }
        return super.handleExceptionInternal(failure, body, headers, status, request);
    }

    private static String codeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "NOT_FOUND";
            case 400 -> "VALIDATION_FAILED";
            default -> "HTTP_" + status.value();
        };
    }

    private Object render(ProblemDetail problem, HttpServletRequest request) {
        if (request.getAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE) != null && navigates(request)) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("status", problem.getStatus());
            model.put("reason", HttpStatus.valueOf(problem.getStatus()).getReasonPhrase());
            model.put("code", problem.getProperties() == null ? "" : problem.getProperties().get("code"));
            model.put("detail", problem.getDetail());
            model.put("traceId", problem.getProperties() == null ? null : problem.getProperties().get("traceId"));
            return new ModelAndView("error", model, HttpStatus.valueOf(problem.getStatus()));
        }
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** A browser navigation names {@code text/html} in its Accept header; an API client does not. */
    private static boolean navigates(HttpServletRequest request) {
        return new ServletServerHttpRequest(request).getHeaders().getAccept().stream()
                .anyMatch(MediaType.TEXT_HTML::equalsTypeAndSubtype);
    }

}
