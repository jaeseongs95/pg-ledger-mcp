package org.pgledger.cli.deploy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The names of the options of {@code render-deploy} ({@code --name=value}). */
public final class DeployOptions {

    /** {@code linux} (the default) or {@code windows}: the one option that tells the targets apart. */
    public static final String PLATFORM = "platform";
    /** Windows only: the directory of the installation (jars and service wrappers, settings, secrets, logs). */
    public static final String HOME = "home";
    public static final String JAVA = "java";
    public static final String JDBC_URL = "jdbc-url";
    /** The application account: the one database account of the MCP server, the admin web and the owner commands. */
    public static final String APP_ACCOUNT = "app-account";
    public static final String SCHEMA = "schema";
    public static final String LONG_TEXT_TYPE = "long-text-type";
    public static final String MCP_PORT = "mcp-port";
    public static final String ADMIN_PORT = "admin-port";
    /** The public host names of the MCP server: one name or a comma list, each allowed in its Host check and served by the nginx site. */
    public static final String MCP_HOST = "mcp-host";
    /** The public host names of the admin web, in the form of {@link #MCP_HOST}; optional (the admin web is then reached on loopback only). */
    public static final String ADMIN_HOST = "admin-host";
    /** Linux only: write the nginx site (the reverse proxy in front of the two services); absent writes none. */
    public static final String NGINX_CONF = "nginx-conf";
    public static final String TLS_CERTIFICATE = "tls-certificate";
    public static final String TLS_KEY = "tls-key";
    /** A flag, Linux only, with {@link #NGINX_CONF}: the certificate is made and renewed by {@code tls-issue} and {@code tls-renew} (ACME); not with the operator's own certificate. */
    public static final String TLS_ACME = "tls-acme";
    /** The ACME directory URL of the certificate authority (an option of {@code tls-issue} and {@code tls-renew} too); with {@link #TLS_ACME} only. */
    public static final String ACME_DIRECTORY = "acme-directory";
    /**
     * The name of the instance, default {@code pg-ledger-mcp}: the service names ({@code <name>} and {@code <name>-admin}),
     * the Linux service user, the jar, settings and secret directories and the WinSW ids all follow it, so that two instances
     * can live on one host (each with its own ports).
     */
    public static final String NAME = "name";
    /**
     * Not rendered into any file: the environment file of a 1.x installation ({@code deploy/install.sh --from-1x-env}). The public host
     * names it allows must be carried by {@link #MCP_HOST} and {@link #ADMIN_HOST}, or the command is refused before anything is
     * changed ({@link LegacyHosts}).
     */
    public static final String FROM_1X_ENV = "from-1x-env";
    /** Not rendered into any file: a directory the files are written below instead of at their own paths. */
    public static final String ROOT = "root";
    /** {@code prepare-host} on Windows: the WinSW executable, copied once for each of the two services; no value on Linux. */
    public static final String WINSW = "winsw";
    /** A flag, not a value: validate every option as a run does, write nothing (the installers call it before they change anything). */
    public static final String CHECK = "check";

    /** The options that take a value, in the order of their usage text. */
    public static final List<String> ALL = List.of(NAME, PLATFORM, HOME, JAVA, JDBC_URL, APP_ACCOUNT, SCHEMA, LONG_TEXT_TYPE,
            MCP_PORT, ADMIN_PORT, MCP_HOST, ADMIN_HOST, NGINX_CONF, TLS_CERTIFICATE, TLS_KEY, ACME_DIRECTORY, FROM_1X_ENV, ROOT);

    /** Every option of the command, as the set the runner checks the command line against. */
    public static final Set<String> NAMES = Stream.concat(ALL.stream(), Stream.of(CHECK, TLS_ACME))
            .collect(Collectors.toUnmodifiableSet());

    private DeployOptions() {
    }
}
