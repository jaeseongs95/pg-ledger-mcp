package org.pgledger.admin.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Settings of the admin web ({@code ledger.admin.*}). The two tokens are secrets: in a deployment they arrive through
 * the secret store (a configtree property source), so their values are never printed. Boot prints the rejected value
 * of a binding violation, so no constraint sits on a secret itself: the rules about the agent token are a boolean
 * ({@link #agentTokenUsable()}), whose failure names only the key, and {@code AdminTokenProvider} checks the admin
 * token the same way.
 *
 * @param token           the admin token of the owner (at least 16 characters)
 * @param agentToken      the bearer token of the MCP agents, required: presenting it here is refused with
 *                        AGENT_TOKEN_FORBIDDEN, so it must be known and must differ from the admin token (without it an
 *                        agent that holds the admin token could not be told from the owner)
 * @param allowedHosts    host names a request may carry in its Host header (case does not matter)
 * @param maxRequestSize  the largest request body
 * @param driverFile      the JDBC driver registration file that the driver page writes (read by every process through
 *                        {@code spring.config.import})
 * @param driverDirectory the one directory a driver jar must be in to be registered (after symbolic links are
 *                        resolved); the driver page runs the code of the jar it checks, so it takes no jar from anywhere
 * @param lockout         the lock after failed token attempts
 * @param oidc            OpenID Connect sign-in: who may sign in (the provider itself is
 *                        {@code spring.security.oauth2.client.*})
 * @param mcp             the MCP server health check of the overview
 */
@Validated
@ConfigurationProperties(prefix = "ledger.admin")
public record LedgerAdminProperties(String token, String agentToken,
        @DefaultValue({"127.0.0.1", "localhost"}) @NotEmpty List<String> allowedHosts,
        @DefaultValue("64KB") DataSize maxRequestSize,
        @DefaultValue("/etc/pg-ledger-mcp/jdbc-driver.yaml") Path driverFile,
        @DefaultValue("/etc/pg-ledger-mcp/drivers") Path driverDirectory,
        @DefaultValue @Valid Lockout lockout, @DefaultValue @Valid Oidc oidc, @DefaultValue @Valid Mcp mcp) {

    /** The shortest token that is accepted as a secret. */
    public static final int MIN_TOKEN_LENGTH = 16;

    public LedgerAdminProperties {
        allowedHosts = List.copyOf(allowedHosts);
    }

    /** The secrets never appear in a log line or a failure text made from this record. */
    @Override
    public String toString() {
        return "LedgerAdminProperties[token=<hidden>, agentToken=<hidden>, allowedHosts=" + allowedHosts
                + ", maxRequestSize=" + maxRequestSize + ", driverFile=" + driverFile + ", driverDirectory="
                + driverDirectory + ", lockout=" + lockout + ", oidc=" + oidc + ", mcp=" + mcp + "]";
    }

    /**
     * The agent token is present, long enough and not the admin token. Evaluated by Bean Validation when the
     * properties are bound, so a deployment without it (or with the owner's token as the agents' token) does not start.
     */
    @AssertTrue(message = "ledger.admin.agent-token must be set to at least 16 characters and must differ from "
            + "ledger.admin.token")
    public boolean isAgentTokenUsable() {
        return agentToken != null && !agentToken.isBlank() && agentToken.length() >= MIN_TOKEN_LENGTH
                && !Objects.equals(agentToken, token);
    }

    /**
     * Failed token attempts: {@code maxFailures} of one client within {@code window} lock that client for
     * {@code lockPeriod}; and {@code globalMaxFailures} failures of all clients together within {@code window} lock
     * every client for {@code lockPeriod}, whatever the addresses are (a client that changes its address, or fills
     * the table of known clients, still meets this cap).
     */
    public record Lockout(@DefaultValue("5") @Min(1) int maxFailures, @DefaultValue("60s") Duration window,
            @DefaultValue("60s") Duration lockPeriod, @DefaultValue("50") @Min(1) int globalMaxFailures) {
    }

    /**
     * Who may sign in with OpenID Connect: each entry names the issuer it counts for; a subject or an e-mail address of
     * the list is accepted from that issuer only.
     */
    public record Oidc(@DefaultValue List<@Valid Owner> owners) {

        public Oidc {
            owners = List.copyOf(owners);
        }

        /**
         * One person of the allow list.
         *
         * @param issuer  the {@code iss} of the ID token (as the provider publishes it)
         * @param subject the {@code sub} of the person at that issuer (optional when the e-mail is given)
         * @param email   the e-mail address the issuer verified (optional when the subject is given; case ignored)
         */
        public record Owner(@NotBlank String issuer, String subject, String email) {

            /** At least one of the subject and the e-mail address names the person. */
            @AssertTrue(message = "an entry of ledger.admin.oidc.owners needs a subject or an email")
            public boolean isIdentified() {
                return StringUtils.hasText(subject) || StringUtils.hasText(email);
            }
        }
    }

    /**
     * The health URL of the MCP server (http or https, without user information); absent means the overview shows
     * "not configured".
     */
    public record Mcp(String healthUrl, @DefaultValue("2s") Duration timeout) {

        /**
         * The URL is an http or https URL with a host and without user information: a password in a URL would be
         * written to the logs (the check is a boolean, so the failure never prints the URL).
         */
        @AssertTrue(message = "ledger.admin.mcp.health-url must be an http or https URL with a host and without "
                + "user information")
        public boolean isHealthUrlUsable() {
            if (!StringUtils.hasText(healthUrl)) {
                return true;
            }
            var url = UriComponentsBuilder.fromUriString(healthUrl).build();
            String scheme = url.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return web && StringUtils.hasText(url.getHost()) && url.getUserInfo() == null;
        }
    }
}
