package org.pgledger.mcp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a request whose declared body is larger than the configured size with 413 before the handler reads it.
 * The refusal is an {@link ErrorResponseException} that the one HTTP error advice renders as a problem detail.
 *
 * <p>ceiling: a body that is sent in chunks declares no length and is not limited here; the clients of the agents
 * send a length, and the container limits the rest of the request.
 */
public final class RequestSizeInterceptor implements HandlerInterceptor {

    private final long maxBytes;
    private final MessageSourceAccessor messages;

    public RequestSizeInterceptor(LedgerMcpProperties properties, MessageSourceAccessor modelMessages) {
        this.maxBytes = properties.maxRequestSize().toBytes();
        this.messages = modelMessages;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getContentLengthLong() > maxBytes) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                    messages.getMessage(AdminCode.PAYLOAD_TOO_LARGE.messageKey()));
            problem.setProperty("code", AdminCode.PAYLOAD_TOO_LARGE.token());
            throw new ErrorResponseException(HttpStatus.CONTENT_TOO_LARGE, problem, null);
        }
        return true;
    }
}
