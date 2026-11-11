package org.pgledger.cli.deploy;

import java.util.List;
import java.util.Set;

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
    public static final String MCP_HOST = "mcp-host";
    public static final String ADMIN_HOST = "admin-host";
    /** Linux only: write the nginx site (the reverse proxy in front of the two services); absent writes none. */
    public static final String NGINX_CONF = "nginx-conf";
    public static final String TLS_CERTIFICATE = "tls-certificate";
    public static final String TLS_KEY = "tls-key";
    /** Not rendered into any file: a directory the files are written below instead of at their own paths. */
    public static final String ROOT = "root";

    /** Every option of the command, in the order of its usage text. */
    public static final List<String> ALL = List.of(PLATFORM, HOME, JAVA, JDBC_URL, APP_ACCOUNT, SCHEMA, LONG_TEXT_TYPE,
            MCP_PORT, ADMIN_PORT, MCP_HOST, ADMIN_HOST, NGINX_CONF, TLS_CERTIFICATE, TLS_KEY, ROOT);

    /** The options as the set the runner checks the command line against. */
    public static final Set<String> NAMES = Set.copyOf(ALL);

    private DeployOptions() {
    }
}
