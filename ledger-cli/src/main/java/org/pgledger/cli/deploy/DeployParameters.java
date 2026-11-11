package org.pgledger.cli.deploy;

import java.util.Map;
import java.util.regex.Pattern;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.pgledger.database.schema.GrantApplier;

/**
 * What the operator decides about the deployment, validated. The values are written into unit files, XML and YAML settings
 * and an nginx site, where a space, a quote, a newline, {@code $} (a Spring placeholder, an nginx variable), {@code %} (a
 * systemd specifier) or {@code &lt;} would change what the file means, so every value is restricted to the plain characters
 * it needs and a value that is not plain is refused ({@link ErrorCode#INVALID_DEPLOY_VALUE}, naming the option and never
 * the value: a JDBC URL may hold a secret). Nothing is quoted or escaped to make a value pass.
 *
 * <p>The three targets differ by options and by nothing else: a personal PC and a Windows server are
 * {@code --platform=windows} (the server adds host names, and puts a reverse proxy of its own in front when it is reached
 * from outside), a Linux server is {@code --platform=linux} and can ask for the nginx site.
 *
 * @param layout       where the files go
 * @param java         absolute path of the {@code java} executable of the Java 25 runtime
 * @param jdbcUrl      JDBC URL of the ledger database; it never holds a password (the passwords are secrets of the secret store)
 * @param appAccount   the application account: the one database account of the MCP server, the admin web and the owner commands
 * @param schema       {@code ledger.schema.name}, or null for the default schema of the account
 * @param longTextType {@code ledger.schema.long-text-type}, or null for the default
 * @param mcpPort      loopback port of the MCP server
 * @param adminPort    loopback port of the admin web
 * @param mcpHost      public host name of the MCP server (allowed in its Host check), or null
 * @param adminHost    public host name of the admin web, or null
 * @param nginx        the nginx site (Linux), or null when none is wanted
 */
public record DeployParameters(DeployLayout layout, String java, String jdbcUrl, String appAccount, String schema,
        String longTextType, int mcpPort, int adminPort, String mcpHost, String adminHost, Nginx nginx) {

    public static final int DEFAULT_MCP_PORT = 8765;
    public static final int DEFAULT_ADMIN_PORT = 8865;

    private static final int FIRST_UNPRIVILEGED_PORT = 1024;
    private static final int LAST_PORT = 65535;
    private static final int MAX_PATH = 200;
    private static final int MAX_HOST = 253;
    private static final int MAX_URL = 600;

    /** Plain path segments only: letters, digits and {@code . _ + -}; {@code .} and {@code ..} are refused separately. */
    private static final Pattern POSIX_PATH = Pattern.compile("(?:/[A-Za-z0-9._+-]+)+");
    /** A drive and plain segments, with {@code /}: the form every path of a Windows installation is written in. */
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:(?:/[A-Za-z0-9._+-]+)+");
    /** The Java runtime of Windows lives in a directory with a space ({@code Program Files}); it is only ever an XML element. */
    private static final Pattern WINDOWS_JAVA = Pattern.compile("[A-Za-z]:(?:/[A-Za-z0-9._+ -]+)+");
    private static final Pattern HOST = Pattern
            .compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    /** The characters of a URI without quotes, backslash, {@code $}, braces and white space. */
    private static final Pattern URL = Pattern.compile("jdbc:[A-Za-z0-9.]+:[A-Za-z0-9._~:/?#\\[\\]@!&*+,;=%()-]+");
    private static final Pattern SECRET_WORD = Pattern.compile("(?i)pass(?:word|wd)|pwd");
    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,29}");
    private static final Pattern LONG_TEXT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,30}");

    /** The nginx site: the file and the TLS certificate and key of the operator (nothing issues or renews them). */
    public record Nginx(String conf, String certificate, String key) {

        public Nginx {
            conf = path(conf, POSIX_PATH, DeployOptions.NGINX_CONF);
            certificate = path(certificate, POSIX_PATH, DeployOptions.TLS_CERTIFICATE);
            key = path(key, POSIX_PATH, DeployOptions.TLS_KEY);
        }
    }

    public DeployParameters {
        boolean windows = layout.platform() == Platform.WINDOWS;
        java = path(java, windows ? WINDOWS_JAVA : POSIX_PATH, DeployOptions.JAVA);
        jdbcUrl = url(jdbcUrl);
        appAccount = account(appAccount);
        schema = optional(schema, SCHEMA, DeployOptions.SCHEMA);
        longTextType = optional(longTextType, LONG_TEXT, DeployOptions.LONG_TEXT_TYPE);
        mcpPort = port(mcpPort, DeployOptions.MCP_PORT);
        adminPort = port(adminPort, DeployOptions.ADMIN_PORT);
        if (mcpPort == adminPort) {
            throw refused(DeployOptions.ADMIN_PORT);
        }
        mcpHost = host(mcpHost, DeployOptions.MCP_HOST);
        adminHost = host(adminHost, DeployOptions.ADMIN_HOST);
        if (mcpHost != null && mcpHost.equals(adminHost)) {
            throw refused(DeployOptions.ADMIN_HOST);
        }
        if (nginx != null) {
            if (windows) {
                // nginx is not part of a Windows installation (see deploy/install.cmd): a Windows server that is reached from
                // outside is put behind a reverse proxy the operator has prepared
                throw refused(DeployOptions.NGINX_CONF);
            }
            if (mcpHost == null || adminHost == null) {
                // the site serves the two public host names: without them there is nothing to serve
                throw refused(mcpHost == null ? DeployOptions.MCP_HOST : DeployOptions.ADMIN_HOST);
            }
        }
    }

    /**
     * The parameters from the values of the options of the command line.
     *
     * @param values option name to value, only the options that were given
     * @throws UsageException MISSING_ARGUMENT for a required option that is absent; INVALID_DEPLOY_VALUE for a value
     *         that is not plain or not possible
     */
    public static DeployParameters of(Map<String, String> values) {
        String platform = values.getOrDefault(DeployOptions.PLATFORM, "linux");
        String home = values.get(DeployOptions.HOME);
        DeployLayout layout;
        if ("linux".equals(platform)) {
            if (home != null) {
                throw refused(DeployOptions.HOME);
            }
            layout = DeployLayout.linux();
        } else if ("windows".equals(platform)) {
            layout = DeployLayout.windows(path(require(home), WINDOWS_PATH, DeployOptions.HOME));
        } else {
            throw refused(DeployOptions.PLATFORM);
        }
        String conf = values.get(DeployOptions.NGINX_CONF);
        String certificate = values.get(DeployOptions.TLS_CERTIFICATE);
        String key = values.get(DeployOptions.TLS_KEY);
        Nginx nginx = null;
        if (conf != null) {
            nginx = new Nginx(conf, require(certificate), require(key));
        } else if (certificate != null || key != null) {
            // a certificate without a site is a mistake of the command line, not a setting to ignore
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        return new DeployParameters(layout, require(values.get(DeployOptions.JAVA)),
                require(values.get(DeployOptions.JDBC_URL)), require(values.get(DeployOptions.APP_ACCOUNT)),
                values.get(DeployOptions.SCHEMA), values.get(DeployOptions.LONG_TEXT_TYPE),
                number(values.get(DeployOptions.MCP_PORT), DEFAULT_MCP_PORT, DeployOptions.MCP_PORT),
                number(values.get(DeployOptions.ADMIN_PORT), DEFAULT_ADMIN_PORT, DeployOptions.ADMIN_PORT),
                values.get(DeployOptions.MCP_HOST), values.get(DeployOptions.ADMIN_HOST), nginx);
    }

    private static String require(String value) {
        if (value == null) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        return value;
    }

    private static int number(String text, int fallback, String option) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, option);
        }
    }

    private static UsageException refused(String option) {
        return new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
    }

    private static String path(String value, Pattern form, String option) {
        if (value == null || value.length() > MAX_PATH || !form.matcher(value).matches()) {
            throw refused(option);
        }
        for (String segment : value.replaceFirst("^[A-Za-z]:", "").substring(1).split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw refused(option);
            }
        }
        return value;
    }

    private static String url(String value) {
        if (value == null || value.length() > MAX_URL || !URL.matcher(value).matches()
                || SECRET_WORD.matcher(value).find()) {
            throw refused(DeployOptions.JDBC_URL);
        }
        return value;
    }

    private static String account(String value) {
        if (!GrantApplier.isAccountName(value)) {
            throw refused(DeployOptions.APP_ACCOUNT);
        }
        return value;
    }

    private static String optional(String value, Pattern pattern, String option) {
        if (value != null && !pattern.matcher(value).matches()) {
            throw refused(option);
        }
        return value;
    }

    private static int port(int value, String option) {
        if (value < FIRST_UNPRIVILEGED_PORT || value > LAST_PORT) {
            throw refused(option);
        }
        return value;
    }

    private static String host(String value, String option) {
        if (value != null && (value.length() > MAX_HOST || !HOST.matcher(value).matches())) {
            throw refused(option);
        }
        return value;
    }
}
