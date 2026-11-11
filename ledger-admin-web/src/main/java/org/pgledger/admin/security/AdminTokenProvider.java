package org.pgledger.admin.security;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.value.Sha256;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * The one provider of the token: the login form (the password field) and the {@code Authorization: Bearer} header
 * both end here. Presented and expected tokens are hashed first (the shared SHA-256 of the domain) and the digests are
 * compared with {@link MessageDigest#isEqual}, which takes the same time whatever the input is. A locked client
 * address is refused before anything is compared (so a right token does not help during the lock); the token of the
 * MCP agents is refused with its own code and counts as a failure. No token is ever written to a message or a log.
 */
@Component
public final class AdminTokenProvider implements AuthenticationProvider {

    /** The shortest token that is accepted as the secret of the owner. */
    static final int MIN_TOKEN_LENGTH = 16;

    private static final String UNKNOWN_CLIENT = "unknown";

    private final byte[] expected;
    private final byte[] agent;
    private final LoginAttempts attempts;

    /**
     * @throws IllegalArgumentException when the token is missing, blank or too short, or equals the agent token; the
     *         message names the key and never the value
     */
    public AdminTokenProvider(LedgerAdminProperties properties, LoginAttempts attempts) {
        String token = properties.token();
        if (token == null || token.isBlank() || token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("ledger.admin.token must be set to at least " + MIN_TOKEN_LENGTH
                    + " characters");
        }
        this.expected = digest(token);
        String agentToken = properties.agentToken();
        this.agent = agentToken == null || agentToken.isBlank() ? null : digest(agentToken);
        if (agent != null && MessageDigest.isEqual(agent, expected)) {
            throw new IllegalArgumentException("ledger.admin.agent-token must differ from ledger.admin.token");
        }
        this.attempts = attempts;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String client = authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress() : UNKNOWN_CLIENT;
        if (attempts.isLocked(client)) {
            throw new AdminAuthenticationException(AdminCode.TOO_MANY_AUTH_FAILURES);
        }
        byte[] presented = digest(presentedToken(authentication));
        if (agent != null && MessageDigest.isEqual(agent, presented)) {
            attempts.failed(client);
            throw new AdminAuthenticationException(AdminCode.AGENT_TOKEN_FORBIDDEN);
        }
        if (!MessageDigest.isEqual(expected, presented)) {
            attempts.failed(client);
            throw new AdminAuthenticationException(AdminCode.UNAUTHORIZED);
        }
        attempts.succeeded(client);
        return UsernamePasswordAuthenticationToken.authenticated("owner", null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication)
                || UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static String presentedToken(Authentication authentication) {
        return authentication instanceof BearerTokenAuthenticationToken bearer ? bearer.getToken()
                : String.valueOf(authentication.getCredentials());
    }

    private static byte[] digest(String token) {
        return HexFormat.of().parseHex(Sha256.ofText(token).hex());
    }
}
