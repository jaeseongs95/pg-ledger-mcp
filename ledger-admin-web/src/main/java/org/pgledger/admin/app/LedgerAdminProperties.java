package org.pgledger.admin.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the admin web ({@code ledger.admin.*}). The two tokens are secrets: in a deployment they arrive through
 * the secret store (a configtree property source), so their values are never printed. They carry no Bean Validation
 * constraint: Boot prints the rejected value of a binding violation, so {@code AdminTokenProvider} checks them and
 * names only the key.
 *
 * @param token          the admin token of the owner (at least 16 characters)
 * @param agentToken     the bearer token of the MCP agents, when the admin knows it; presenting it here is refused
 *                       with AGENT_TOKEN_FORBIDDEN, and it must differ from the admin token
 * @param allowedHosts   host names a request may carry in its Host header (case does not matter)
 * @param maxRequestSize the largest request body
 * @param driverFile     the JDBC driver registration file that the driver page writes (read by every process through
 *                       {@code spring.config.import})
 * @param lockout        the lock after failed token attempts
 * @param oidc           OpenID Connect sign-in: who may sign in (the provider itself is
 *                       {@code spring.security.oauth2.client.*})
 * @param mcp            the MCP server health check of the overview
 */
@Validated
@ConfigurationProperties(prefix = "ledger.admin")
public record LedgerAdminProperties(String token, String agentToken,
        @DefaultValue({"127.0.0.1", "localhost"}) @NotEmpty List<String> allowedHosts,
        @DefaultValue("64KB") DataSize maxRequestSize,
        @DefaultValue("/etc/pg-ledger-mcp/jdbc-driver.yaml") Path driverFile,
        @DefaultValue @Valid Lockout lockout, @DefaultValue @Valid Oidc oidc, @DefaultValue @Valid Mcp mcp) {

    public LedgerAdminProperties {
        allowedHosts = List.copyOf(allowedHosts);
    }

    /** The secrets never appear in a log line or a failure text made from this record. */
    @Override
    public String toString() {
        return "LedgerAdminProperties[token=<hidden>, agentToken=<hidden>, allowedHosts=" + allowedHosts
                + ", maxRequestSize=" + maxRequestSize + ", driverFile=" + driverFile + ", lockout=" + lockout
                + ", oidc=" + oidc + ", mcp=" + mcp + "]";
    }

    /**
     * Failed token attempts of one client address: {@code maxFailures} within {@code window} lock that address for
     * {@code lockPeriod}.
     */
    public record Lockout(@DefaultValue("5") @Min(1) int maxFailures, @DefaultValue("60s") Duration window,
            @DefaultValue("60s") Duration lockPeriod) {
    }

    /** The e-mail addresses (verified by the provider) that may sign in with OpenID Connect. */
    public record Oidc(@DefaultValue List<String> allowedEmails) {

        public Oidc {
            allowedEmails = List.copyOf(allowedEmails);
        }
    }

    /** The health URL of the MCP server (http or https); absent means the overview shows "not configured". */
    public record Mcp(@Pattern(regexp = "https?://.+") String healthUrl, @DefaultValue("2s") Duration timeout) {
    }
}
