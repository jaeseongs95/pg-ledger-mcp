// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param lockout         the delay after many failed credential attempts (a budget of all callers, no lock)
 * @param oidc            OpenID Connect sign-in: the one owner (the provider itself is
 *                        {@code spring.security.oauth2.client.*})
 * @param mcp             the MCP server health check of the overview
 * @param signIn          how the owner signs in, {@code token}, {@code oidc} or {@code passkey}; absent means {@code passkey} for a new
 *                        installation, and an installation that has OpenID Connect settings but no choice is refused at start
 *                        (nothing is guessed)
 * @param passkey         the relying party of the passkeys (owner sign-in and one-time enrollment registration)
 */
@Validated
@ConfigurationProperties(prefix = "ledger.admin")
public record LedgerAdminProperties(String token, String agentToken,
        @DefaultValue({"127.0.0.1", "localhost"}) @NotEmpty List<String> allowedHosts,
        @DefaultValue("64KB") DataSize maxRequestSize,
        @DefaultValue("/etc/veridger/driver-state/jdbc-driver.yaml") Path driverFile,
        @DefaultValue("/etc/veridger/drivers") Path driverDirectory,
        @DefaultValue @Valid Lockout lockout, @DefaultValue @Valid Oidc oidc, @DefaultValue @Valid Mcp mcp,
        SignInMethod signIn, @DefaultValue @Valid Passkey passkey, @DefaultValue @Valid Update update) {

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
                + driverDirectory + ", lockout=" + lockout + ", oidc=" + oidc + ", mcp=" + mcp + ", signIn=" + signIn
                + ", passkey=" + passkey + ", update=" + update + "]";
    }

    /** The maximum request size must be at least 1 byte and fit in an in-memory buffer check ({@code maxBytes + 1}). */
    @AssertTrue(message = "ledger.admin.max-request-size must be between 1B and 2147483646B")
    public boolean isMaxRequestSizeUsable() {
        return maxRequestSize != null && maxRequestSize.toBytes() >= 1L
                && maxRequestSize.toBytes() < Integer.MAX_VALUE;
    }

    /** The sign-in that is in force: the chosen one, {@code passkey} when none was chosen. */
    public SignInMethod effectiveSignIn() {
        return signIn == null ? SignInMethod.PASSKEY : signIn;
    }

    /**
     * An installation that has OpenID Connect settings must say that it means to sign in with them: nothing is guessed from
     * the presence of settings, so an upgrade from a version that signed in with OpenID Connect stops at start with this
     * message until {@code ledger.admin.sign-in=oidc} is written (or {@code passkey}, to give them up).
     */
    @AssertTrue(message = "ledger.admin.sign-in must be set to oidc or passkey, because ledger.admin.oidc.* is set")
    public boolean isSignInChosenWhenOidcIsSet() {
        return signIn != null || (!StringUtils.hasText(oidc.issuer()) && !StringUtils.hasText(oidc.ownerEmail()));
    }

    /** Signing in with OpenID Connect needs the one e-mail address of the owner and the issuer it counts for. */
    @AssertTrue(message = "ledger.admin.sign-in=oidc needs ledger.admin.oidc.owner-email and ledger.admin.oidc.issuer")
    public boolean isOidcComplete() {
        return signIn != SignInMethod.OIDC
                || (StringUtils.hasText(oidc.issuer()) && StringUtils.hasText(oidc.ownerEmail()));
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
     * Credential attempts (token, one-time code, passkey): {@code globalMaxFailures} checks of all callers together within
     * {@code window} are the budget; every check beyond it waits {@code globalDelay} before the credential is compared. The
     * limit slows down and never locks, so wrong credentials from anyone cannot keep the owner with the right one out
     * (there is no limit per client: see {@code LoginAttempts}).
     */
    public record Lockout(@DefaultValue("60s") Duration window, @DefaultValue("50") @Min(1) int globalMaxFailures,
            @DefaultValue("1s") Duration globalDelay) {

        /** The delay is zero or more (a negative delay would not slow anything down). */
        @AssertTrue(message = "ledger.admin.lockout.global-delay must not be negative")
        public boolean isGlobalDelayUsable() {
            return !globalDelay.isNegative();
        }
    }

    /**
     * Who may sign in with OpenID Connect: the owner is one e-mail address, which the provider verified, at one issuer (an
     * address is only meaningful inside the issuer that verified it: another provider can hand out the same address to
     * somebody else). The personal edition has one owner, so there is no list.
     *
     * @param issuer     the {@code iss} of the ID token as the provider publishes it
     * @param ownerEmail the e-mail address of the owner (case ignored; the provider must have verified it)
     */
    public record Oidc(String issuer, String ownerEmail) {
    }

    /**
     * The relying party of the passkeys. A passkey is bound to a host name and to the origin the browser shows: both default to
     * the one public host of the admin web (the one host of {@code allowed-hosts} that is not a loopback name, with
     * {@code https}), or to {@code localhost} and {@code http://localhost:<server.port>} when there is none. With several
     * public hosts, say which one in {@code rp-id} and list the origins. HTTPS is required for every origin but a loopback one.
     *
     * @param rpId   the relying party id (a host name, never an address)
     * @param origins the origins the browser may show (scheme, host and port as the address bar writes them)
     * @param rpName the name an authenticator shows
     */
    public record Passkey(String rpId, @DefaultValue List<String> origins, @DefaultValue("Ledger admin") String rpName) {

        public Passkey {
            origins = List.copyOf(origins);
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

    /**
     * Settings for the least-privilege Admin Web self-updater ({@code ledger.admin.update.*}). The web process never
     * executes OS commands; it verifies release artifacts against {@code SHA256SUMS} and stages an update request
     * inside {@code directory} for the host's external updater unit or scheduled task.
     */
    public record Update(@DefaultValue("true") boolean enabled,
            @DefaultValue("jaeseongs95/veridger") String repository,
            @DefaultValue("https://api.github.com") String apiBaseUrl,
            @DefaultValue({"github.com", "objects.githubusercontent.com",
                    "release-assets.githubusercontent.com"}) @NotEmpty List<String> allowedDownloadHosts,
            @DefaultValue("/etc/veridger/update") Path directory,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("7.0.0") String currentVersion,
            @DefaultValue("18") @Min(1) int currentSchemaVersion,
            Path schemaMigrationMarker) {

        private static final Pattern REPOSITORY_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

        @ConstructorBinding
        public Update {
            allowedDownloadHosts = allowedDownloadHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        }

        public Update(boolean enabled, String repository, String apiBaseUrl, List<String> allowedDownloadHosts,
                Path directory, Duration timeout, String currentVersion, int currentSchemaVersion) {
            this(enabled, repository, apiBaseUrl, allowedDownloadHosts, directory, timeout, currentVersion,
                    currentSchemaVersion, null);
        }

        @AssertTrue(message = "ledger.admin.update.repository must be of the form owner/repo")
        public boolean isRepositoryUsable() {
            return StringUtils.hasText(repository) && REPOSITORY_PATTERN.matcher(repository).matches();
        }

        @AssertTrue(message = "ledger.admin.update.api-base-url must be an http or https URL with a host and without "
                + "user information")
        public boolean isApiBaseUrlUsable() {
            if (!StringUtils.hasText(apiBaseUrl)) {
                return false;
            }
            try {
                var url = UriComponentsBuilder.fromUriString(apiBaseUrl).build();
                String scheme = url.getScheme();
                boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
                return web && StringUtils.hasText(url.getHost()) && url.getUserInfo() == null;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        @AssertTrue(message = "ledger.admin.update.timeout must be positive")
        public boolean isTimeoutUsable() {
            return timeout != null && !timeout.isNegative() && !timeout.isZero();
        }
    }
}
