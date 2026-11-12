package org.pgledger.cli.deploy;

import java.util.regex.Pattern;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;

/**
 * The plain forms a deployment value may have, as the regular expressions of the Jakarta constraints of
 * {@link DeployParameters}. The values are written into unit files, XML and YAML settings and an nginx site, where a space, a
 * quote, a newline, {@code $} (a Spring placeholder, an nginx variable), {@code %} (a systemd specifier) or {@code <} would
 * change what the file means, so each value is restricted to the plain characters it needs. Nothing is quoted or escaped to
 * make a value pass.
 */
public final class DeployForms {

    static final int FIRST_UNPRIVILEGED_PORT = 1024;
    static final int LAST_PORT = 65535;
    static final int MAX_PATH = 200;
    static final int MAX_HOST = 253;
    static final int MAX_URL = 600;

    /** A path segment of plain characters; {@code .} and {@code ..} are no segment (they would leave the directory). */
    private static final String SEGMENT = "(?!\\.{1,2}(?:/|$))[A-Za-z0-9._+-]+";
    /** Plain path segments only: letters, digits and {@code . _ + -}. */
    public static final String POSIX_PATH = "(?:/" + SEGMENT + ")+";
    /** A drive and plain segments, with {@code /}: the form every path of a Windows installation is written in. */
    public static final String WINDOWS_PATH = "[A-Za-z]:(?:/" + SEGMENT + ")+";
    /** The Java runtime of Windows lives in a directory with a space ({@code Program Files}); it is only ever an XML element. */
    static final String WINDOWS_JAVA = "[A-Za-z]:(?:/(?!\\.{1,2}(?:/|$))[A-Za-z0-9._+ -]+)+";
    static final String HOST = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*";
    /**
     * The characters of a URI without quotes, backslash, {@code $}, braces and white space, and no password word anywhere:
     * a JDBC URL never holds a password (the passwords are secrets of the secret store).
     */
    static final String URL = "(?!.*(?i:pass(?:word|wd)|pwd))jdbc:[A-Za-z0-9.]+:[A-Za-z0-9._~:/?#\\[\\]@!&*+,;=%()-]+";
    /**
     * The name of an instance becomes a system user, unit and service names, directory names and a file name, so it is a short
     * plain word: lower-case letters, digits and hyphens, a letter first, a letter or digit last, at most 23 characters (the
     * user name of a Linux system is at most 32 and the admin service adds {@code -admin}).
     */
    static final String NAME = "[a-z](?:[a-z0-9-]{0,21}[a-z0-9])?";
    /**
     * The ACME directory URL of an installation: https, a host name, an optional port and plain path segments. It is written into
     * a systemd unit, where {@code %} is a specifier, so nothing else is allowed (no user part, no query, no escape).
     */
    static final String ACME_URL = "https://[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?(?::[0-9]{1,5})?(?:/[A-Za-z0-9._+-]+)*";
    static final String SCHEMA ="[a-z][a-z0-9_]{0,29}";
    static final String LONG_TEXT = "[A-Za-z][A-Za-z0-9_]{0,30}";

    private DeployForms() {
    }

    /**
     * The value when it is a plain host name (the form of {@code --mcp-host}).
     *
     * @throws UsageException INVALID_DEPLOY_VALUE naming the option, never the value
     */
    public static String requireHost(String value, String option) {
        if (value == null || value.length() > MAX_HOST || !Pattern.matches(HOST, value)) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
        }
        return value;
    }

    /**
     * The value when it is the name of an instance.
     *
     * @throws UsageException INVALID_DEPLOY_VALUE naming the option, never the value
     */
    public static String requireName(String value, String option) {
        if (value == null || !Pattern.matches(NAME, value) || value.endsWith("-admin")) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
        }
        return value;
    }

    /** Whether the value has the form and is not longer than a path may be. */
    public static boolean isPath(String value, String form) {
        return value != null && value.length() <= MAX_PATH && Pattern.matches(form, value);
    }

    /**
     * The value when it is a path of that form.
     *
     * @throws UsageException INVALID_DEPLOY_VALUE naming the option, never the value
     */
    public static String requirePath(String value, String form, String option) {
        if (!isPath(value, form)) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
        }
        return value;
    }
}
