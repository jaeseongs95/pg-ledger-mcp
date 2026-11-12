package org.pgledger.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.pgledger.admin.app.AdminMode;
import org.pgledger.admin.web.AdminProblems;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Every refusal of the security chain ends here and nothing is written here: the failure is handed to the
 * {@link HandlerExceptionResolver}, so the one HTTP error advice renders the problem detail (principle 3.8). The only
 * thing decided here is where a browser goes after a failed sign-in: back to the login page (with the code of the
 * failure), while any other client gets the problem itself.
 */
@Component
public final class SecurityFailures implements AuthenticationEntryPoint, AuthenticationFailureHandler,
        AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;
    private final LocaleResolver locales;
    private final AdminProblems problems;
    private final AdminMode mode;
    private final MediaTypeRequestMatcher browser = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
    private final RedirectStrategy redirects = new DefaultRedirectStrategy();

    public SecurityFailures(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            LocaleResolver locales, AdminProblems problems, AdminMode mode) {
        this.resolver = resolver;
        this.locales = locales;
        this.problems = problems;
        this.mode = mode;
        this.browser.setIgnoredMediaTypes(Set.of(MediaType.ALL));
    }

    /** No credentials, for a client that wants a problem (the JSON endpoints): 401. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) {
        resolver.resolveException(request, response, null, exception(failure, request, false));
    }

    /** A failed sign-in (login form, bearer token, OpenID Connect). */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        Exception problem = exception(failure, request, true);
        if (browser.matches(request) && problem instanceof AdminException admin) {
            redirects.sendRedirect(request, response, "/login?error=" + admin.code().token());
            return;
        }
        resolver.resolveException(request, response, null, problem);
    }

    /** A request that is allowed to run only with something it does not have: the CSRF token, or the setup mode. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure) {
        Exception problem;
        if (failure instanceof CsrfException) {
            problem = new AdminException(AdminCode.CSRF_REJECTED, List.of());
        } else if (mode == AdminMode.SETUP) {
            problem = new InfrastructureException(InfrastructureCode.DRIVER_NOT_CONFIGURED, List.of());
        } else {
            problem = new AdminException(AdminCode.UNAUTHORIZED, List.of());
        }
        resolver.resolveException(request, response, null, problem);
    }

    private Exception exception(AuthenticationException failure, HttpServletRequest request, boolean identity) {
        return switch (failure) {
            case AdminAuthenticationException admin -> new AdminException(admin.code(), List.of());
            case OAuth2AuthenticationException oauth when identity -> new ErrorResponseException(HttpStatus.FORBIDDEN,
                    problems.problem(HttpStatus.FORBIDDEN, AdminProblems.FORBIDDEN, locales.resolveLocale(request)),
                    oauth);
            default -> new AdminException(AdminCode.UNAUTHORIZED, List.of());
        };
    }
}
