package org.pgledger.cli.secret;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The settings of the environment file of a 1.x installation ({@code /etc/pg-ledger-mcp/pg-ledger-mcp.env}), as the 1.x
 * installer wrote it (commit 9fb62fe, {@code deploy/install.sh}): one {@code KEY=VALUE} per line, the value unquoted, a
 * comment line starting with {@code #}. The 1.x installer itself read a value as the last occurrence of the key. The service
 * read the file as a systemd {@code EnvironmentFile}, which takes more than the installer wrote, so this reads that
 * superset (https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html#EnvironmentFile=): empty lines, lines
 * starting with {@code #} or {@code ;} and lines without a valid name are skipped, the value may be wrapped in one pair of
 * matching quotes, and the last occurrence of a key wins. Line ends are LF, CRLF or CR ({@link String#lines()}). Nothing of the text is kept in a message.
 */
public final class OneXEnvironment {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, String> values;

    private OneXEnvironment(Map<String, String> values) {
        this.values = values;
    }

    public static OneXEnvironment parse(String text) {
        Map<String, String> found = new HashMap<>();
        // String.lines() ends a line at LF, CR or CRLF, so the line end of any platform is not part of a value
        for (String raw : (Iterable<String>) text.lines()::iterator) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 1) {
                continue;
            }
            String name = line.substring(0, equals).strip();
            if (NAME.matcher(name).matches()) {
                found.put(name, unquote(line.substring(equals + 1).strip()));
            }
        }
        return new OneXEnvironment(found);
    }

    /** The value of the last occurrence of the key; empty when there is none or its value is empty. */
    public Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name)).filter(value -> !value.isEmpty());
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
