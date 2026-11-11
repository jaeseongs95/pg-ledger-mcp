package org.pgledger.mcp.security;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.pgledger.domain.value.Sha256;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * Accepts the one bearer token of the agents. Both tokens are hashed first (the shared SHA-256 of the domain) and
 * the digests are compared with {@link MessageDigest#isEqual}, which takes the same time whatever the input is, so
 * neither the content nor the length of the token can be guessed from the response time. The token is never
 * written to a message or a log.
 */
public final class AgentTokenProvider implements AuthenticationProvider {

    private final byte[] expected;

    public AgentTokenProvider(LedgerMcpProperties properties) {
        this.expected = digest(properties.token());
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        BearerTokenAuthenticationToken presented = (BearerTokenAuthenticationToken) authentication;
        if (!MessageDigest.isEqual(expected, digest(presented.getToken()))) {
            throw new BadCredentialsException("The bearer token is not valid.");
        }
        return UsernamePasswordAuthenticationToken.authenticated("agent", null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static byte[] digest(String token) {
        return HexFormat.of().parseHex(Sha256.ofText(token).hex());
    }
}
