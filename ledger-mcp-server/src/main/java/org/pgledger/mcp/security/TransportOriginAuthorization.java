package org.pgledger.mcp.security;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * A request that carries an Origin header comes from a browser page; it is allowed only for an origin of the
 * configured list (the guard against a web page that reaches the local server). A request without an Origin is
 * not a browser request and passes.
 */
public final class TransportOriginAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    private final Set<String> allowed;

    public TransportOriginAuthorization(LedgerMcpProperties properties) {
        this.allowed = Set.copyOf(properties.allowedOrigins().stream()
                .map(origin -> origin.toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        String origin = context.getRequest().getHeader(HttpHeaders.ORIGIN);
        return new AuthorizationDecision(origin == null || allowed.contains(origin.toLowerCase(Locale.ROOT)));
    }
}
