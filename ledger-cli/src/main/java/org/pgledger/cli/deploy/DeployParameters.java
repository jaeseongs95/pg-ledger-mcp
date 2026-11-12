package org.pgledger.cli.deploy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.pgledger.database.schema.GrantApplier;

/**
 * What the operator decides about the deployment: a value record, validated by Jakarta constraints
 * ({@link DeployInputReader} runs the validator, once, where the values come in). The values are written into unit files,
 * XML and YAML settings and an nginx site, where a space, a quote, a newline, {@code $} (a Spring placeholder, an nginx
 * variable), {@code %} (a systemd specifier) or {@code &lt;} would change what the file means, so every value is restricted
 * to the plain characters it needs ({@link DeployForms}) and a value that is not plain is refused
 * ({@code INVALID_DEPLOY_VALUE}, naming the option and never the value: a JDBC URL may hold a secret). The
 * <em>message</em> of every constraint is the name of the option it checks ({@link DeployOptions}), which is all the
 * refusal says; no message template holds the value. Nothing is quoted or escaped to make a value pass.
 *
 * <p>The three targets differ by options and by nothing else: a personal PC and a Windows server are
 * {@code --platform=windows} (the server adds host names, and puts a reverse proxy of its own in front when it is reached
 * from outside), a Linux server is {@code --platform=linux} and can ask for the nginx site.
 *
 * @param layout       where the files go (valid: the name of the instance)
 * @param java         absolute path of the {@code java} executable of the Java 25 runtime
 * @param jdbcUrl      JDBC URL of the ledger database; it never holds a password (the passwords are secrets of the secret store)
 * @param appAccount   the application account: the one database account of the MCP server, the admin web and the owner commands
 * @param schema       {@code ledger.schema.name}, or null for the default schema of the account
 * @param longTextType {@code ledger.schema.long-text-type}, or null for the default
 * @param mcpPort      loopback port of the MCP server
 * @param adminPort    loopback port of the admin web
 * @param mcpHosts     public host names of the MCP server (each allowed in its Host check and served by the nginx site), none when it
 *                     is reached on loopback only
 * @param adminHosts   public host names of the admin web, none when it is reached on loopback only
 * @param nginx        the nginx site (Linux), or null when none is wanted
 */
public record DeployParameters(
        @Valid DeployLayout layout,
        String java,
        @NotNull(message = DeployOptions.JDBC_URL)
        @Size(max = DeployForms.MAX_URL, message = DeployOptions.JDBC_URL)
        @Pattern(regexp = DeployForms.URL, message = DeployOptions.JDBC_URL) String jdbcUrl,
        String appAccount,
        @Pattern(regexp = DeployForms.SCHEMA, message = DeployOptions.SCHEMA) String schema,
        @Pattern(regexp = DeployForms.LONG_TEXT, message = DeployOptions.LONG_TEXT_TYPE) String longTextType,
        @Min(value = DeployForms.FIRST_UNPRIVILEGED_PORT, message = DeployOptions.MCP_PORT)
        @Max(value = DeployForms.LAST_PORT, message = DeployOptions.MCP_PORT) int mcpPort,
        @Min(value = DeployForms.FIRST_UNPRIVILEGED_PORT, message = DeployOptions.ADMIN_PORT)
        @Max(value = DeployForms.LAST_PORT, message = DeployOptions.ADMIN_PORT) int adminPort,
        @Size(max = DeployForms.MAX_HOSTS, message = DeployOptions.MCP_HOST)
        List<@Size(max = DeployForms.MAX_HOST, message = DeployOptions.MCP_HOST)
                @Pattern(regexp = DeployForms.HOST, message = DeployOptions.MCP_HOST) String> mcpHosts,
        @Size(max = DeployForms.MAX_HOSTS, message = DeployOptions.ADMIN_HOST)
        List<@Size(max = DeployForms.MAX_HOST, message = DeployOptions.ADMIN_HOST)
                @Pattern(regexp = DeployForms.HOST, message = DeployOptions.ADMIN_HOST) String> adminHosts,
        @Valid Nginx nginx) {

    /** An absent list of host names is no host names; the lists are never null and never changed. */
    public DeployParameters {
        mcpHosts = mcpHosts == null ? List.of() : List.copyOf(mcpHosts);
        adminHosts = adminHosts == null ? List.of() : List.copyOf(adminHosts);
    }

    /**
     * The nginx site: the file and the TLS certificate and key. They are the operator's own (nothing issues or renews them),
     * or, when {@code acme} is given, the PEM files that {@code tls-issue} and {@code tls-renew} write into the TLS directory
     * of the instance.
     *
     * @param acme the automatic certificate, or null for the operator's own
     */
    public record Nginx(
            @NotNull(message = DeployOptions.NGINX_CONF)
            @Size(max = DeployForms.MAX_PATH, message = DeployOptions.NGINX_CONF)
            @Pattern(regexp = DeployForms.POSIX_PATH, message = DeployOptions.NGINX_CONF) String conf,
            @NotNull(message = DeployOptions.TLS_CERTIFICATE)
            @Size(max = DeployForms.MAX_PATH, message = DeployOptions.TLS_CERTIFICATE)
            @Pattern(regexp = DeployForms.POSIX_PATH, message = DeployOptions.TLS_CERTIFICATE) String certificate,
            @NotNull(message = DeployOptions.TLS_KEY)
            @Size(max = DeployForms.MAX_PATH, message = DeployOptions.TLS_KEY)
            @Pattern(regexp = DeployForms.POSIX_PATH, message = DeployOptions.TLS_KEY) String key,
            @Valid Acme acme) {

        /** The nginx site with the operator's own certificate. */
        public Nginx(String conf, String certificate, String key) {
            this(conf, certificate, key, null);
        }
    }

    /**
     * The automatic certificate (ACME, HTTP-01).
     *
     * @param directory         the ACME directory URL of the certificate authority when the operator chose one, or null for the
     *                          default of {@code tls-issue} (the renewal unit then does not name it)
     * @param certificateIssued whether the certificate files exist already: nginx cannot load a {@code listen 443 ssl} block
     *                          before they do, so until then the site holds the port 80 blocks only ({@code render-deploy} looks
     *                          at the files and says so in its usage)
     */
    public record Acme(
            @Size(max = DeployForms.MAX_URL, message = DeployOptions.ACME_DIRECTORY)
            @Pattern(regexp = DeployForms.ACME_URL, message = DeployOptions.ACME_DIRECTORY) String directory,
            boolean certificateIssued) {
    }

    /** The same parameters, told whether the certificate files of an automatic certificate exist. */
    DeployParameters withCertificateIssued(boolean issued) {
        if (nginx == null || nginx.acme() == null) {
            return this;
        }
        Nginx site = new Nginx(nginx.conf(), nginx.certificate(), nginx.key(), new Acme(nginx.acme().directory(), issued));
        return new DeployParameters(layout, java, jdbcUrl, appAccount, schema, longTextType, mcpPort, adminPort, mcpHosts,
                adminHosts, site);
    }

    /** The Java runtime is a plain path of the form of the platform. */
    @AssertTrue(message = DeployOptions.JAVA)
    public boolean isJavaPlain() {
        return layout != null && DeployForms.isPath(java, layout.platform().javaPathForm());
    }

    @AssertTrue(message = DeployOptions.APP_ACCOUNT)
    public boolean isAppAccountPlain() {
        return GrantApplier.isAccountName(appAccount);
    }

    /** The two services listen on two ports. */
    @AssertTrue(message = DeployOptions.ADMIN_PORT)
    public boolean isAdminPortApart() {
        return mcpPort != adminPort;
    }

    /** No host name is twice in a list. */
    @AssertTrue(message = DeployOptions.MCP_HOST)
    public boolean isMcpHostsDistinct() {
        return new HashSet<>(mcpHosts).size() == mcpHosts.size();
    }

    @AssertTrue(message = DeployOptions.ADMIN_HOST)
    public boolean isAdminHostsDistinct() {
        return new HashSet<>(adminHosts).size() == adminHosts.size();
    }

    /** The two services have their own public host names: a name is served by one of them. */
    @AssertTrue(message = DeployOptions.ADMIN_HOST)
    public boolean isAdminHostApart() {
        return Collections.disjoint(mcpHosts, adminHosts);
    }

    /**
     * nginx is not part of a Windows installation (see {@code deploy/install.cmd}): a Windows server that is reached from
     * outside is put behind a reverse proxy the operator has prepared.
     */
    @AssertTrue(message = DeployOptions.NGINX_CONF)
    public boolean isNginxForThePlatform() {
        return nginx == null || layout == null || layout.platform().servesNginx();
    }

    /**
     * The site serves the public host names of the MCP server: without one there is nothing to serve. The admin web has a block
     * only when it has a public name; without one it is reached on loopback.
     */
    @AssertTrue(message = DeployOptions.MCP_HOST)
    public boolean isMcpHostForTheSite() {
        return nginx == null || !servesNginx() || !mcpHosts.isEmpty();
    }

    private boolean servesNginx() {
        return layout == null || layout.platform().servesNginx();
    }
}
