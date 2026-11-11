package org.pgledger.cli.deploy;

import java.util.List;
import java.util.Set;

/** The names of the options of {@code render-deploy} ({@code --name=value}). */
public final class DeployOptions {

    public static final String JAVA = "java";
    public static final String JDBC_URL = "jdbc-url";
    public static final String MCP_ACCOUNT = "mcp-account";
    public static final String ADMIN_ACCOUNT = "admin-account";
    public static final String SCHEMA = "schema";
    public static final String LONG_TEXT_TYPE = "long-text-type";
    public static final String MCP_PORT = "mcp-port";
    public static final String ADMIN_PORT = "admin-port";
    public static final String MCP_HOST = "mcp-host";
    public static final String ADMIN_HOST = "admin-host";
    public static final String NGINX_CONF = "nginx-conf";
    public static final String TLS_CERTIFICATE = "tls-certificate";
    public static final String TLS_KEY = "tls-key";
    /** Not rendered into any file: the directory the files are written below, {@code /} unless given. */
    public static final String ROOT = "root";

    /** Every option of the command, in the order of its usage text. */
    public static final List<String> ALL = List.of(JAVA, JDBC_URL, MCP_ACCOUNT, ADMIN_ACCOUNT, SCHEMA, LONG_TEXT_TYPE,
            MCP_PORT, ADMIN_PORT, MCP_HOST, ADMIN_HOST, NGINX_CONF, TLS_CERTIFICATE, TLS_KEY, ROOT);

    /** The options as the set the runner checks the command line against. */
    public static final Set<String> NAMES = Set.copyOf(ALL);

    private DeployOptions() {
    }
}
