package org.pgledger.cli.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * The settings of the environment file of a 1.x installation ({@code /etc/pg-ledger-mcp/env}), as the 1.x
 * installer wrote it (tag v1.1.0, {@code deploy/install.sh} with {@code ETC=/etc/$NAME}; the 1.x units read it with
 * {@code EnvironmentFile=@ETC@/env}): one {@code KEY=VALUE} per line, the value unquoted, a
 * comment line starting with {@code #}. The 1.x installer itself read a value as the last occurrence of the key. The service
 * read the file as a systemd {@code EnvironmentFile}, which takes more than the installer wrote, so this reads that
 * superset (https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html#EnvironmentFile=): empty lines, lines
 * starting with {@code #} or {@code ;} and lines without a valid name are skipped, the value may be wrapped in one pair of
 * matching quotes, and the last occurrence of a key wins. Line ends are LF, CRLF or CR ({@link String#lines()}). Nothing of the text is kept in a message.
 */
public final class OneXEnvironment {

    /** The 1.x file has a few lines; this bounds what a wrong input can make the program hold. */
    static final int MAXIMUM_BYTES = 64 * 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, String> values;

    private OneXEnvironment(Map<String, String> values) {
        this.values = values;
    }

    /**
     * Reads the file as the environment of 1.x (at most {@value #MAXIMUM_BYTES} bytes, UTF-8, nothing replaced). Only the kind
     * of a failure is reported, never the text of an exception (it could hold what was read).
     *
     * @throws FailureException ENV_FILE_FAILED naming the kind of the failure
     */
    public static OneXEnvironment read(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            return parse(decode(stream.readNBytes(MAXIMUM_BYTES + 1), ErrorCode.ENV_FILE_FAILED));
        } catch (IOException e) {
            throw new FailureException(ErrorCode.ENV_FILE_FAILED, e.getClass().getSimpleName());
        }
    }

    /** UTF-8, nothing replaced; the bytes are wiped once decoded. A text over the bound is not an environment file of 1.x. */
    static String decode(byte[] bytes, ErrorCode failure) {
        try {
            if (bytes.length > MAXIMUM_BYTES) {
                throw new FailureException(failure, "TooLarge");
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new FailureException(failure, e.getClass().getSimpleName());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
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
