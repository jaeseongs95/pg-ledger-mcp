package org.pgledger.mcp.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one handler of HTTP errors of the server, with the same structure as the one of the admin web: the handler of Spring
 * renders the standard exceptions as problem details, this class adds the failures of the server itself (a request body
 * that is too large) and a last catch-all that logs the failure with a trace id and answers with a plain sentence and
 * nothing of the original failure. Tool failures are not HTTP errors; they are tool results (see {@code ToolErrorMapper}).
 * The sentences are English: the client is a model (see {@code McpMessagesConfiguration}).
 */
@RestControllerAdvice
public class McpHttpExceptionAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(McpHttpExceptionAdvice.class);

    private final MessageSourceAccessor messages;

    public McpHttpExceptionAdvice(MessageSourceAccessor modelMessages) {
        this.messages = modelMessages;
    }

    /** The body is larger than the limit: 413 with the code {@code PAYLOAD_TOO_LARGE}. */
    @ExceptionHandler(RequestTooLargeException.class)
    ResponseEntity<Object> tooLarge(RequestTooLargeException failure, WebRequest request) {
        HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
        return handleExceptionInternal(failure, problem(status, McpHttpCode.PAYLOAD_TOO_LARGE), new HttpHeaders(), status,
                request);
    }

    /** The boundary: anything unexpected is logged with its cause and answered without any internal detail. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure) {
        String traceId = UUID.randomUUID().toString();
        LOG.error("the request failed with {} (trace {})", McpHttpCode.INTERNAL_ERROR, traceId, failure);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, McpHttpCode.INTERNAL_ERROR);
        problem.setProperty("traceId", traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ProblemDetail problem(HttpStatus status, McpHttpCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.getMessage(code.messageKey()));
        problem.setProperty("code", code.name());
        return problem;
    }
}
