package org.pgledger.cli.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * The secret from the first line of standard input, for an installation that runs without a terminal (an automated or
 * remote one). It is the opt-in {@code --stdin} of the commands that take a secret, and the same convention as
 * {@code docker login --password-stdin}: "to run the docker login command non-interactively, you can set the
 * --password-stdin flag to provide a password through STDIN. Using STDIN prevents the password from ending up in the shell's
 * history or log files" (https://docs.docker.com/reference/cli/docker/login/#password-stdin). A secret is never taken from an
 * argument or from the environment.
 *
 * <p>One line is read, byte by byte and no further, so the next line stays in the stream for the next command (the installer
 * gives the password of the application account and then the one of the DDL account on one standard input). The line end
 * (LF or CRLF) is not part of the secret. A line of more than {@value #MAX_BYTES} bytes is refused, text that is not UTF-8
 * is refused, and no refusal holds a byte of the secret. The bytes are wiped after they are decoded.
 */
public final class StdinSecretInput implements SecretInput {

    /** The longest secret: far more than a token or a password, and a bound on what a wrong input can make the program hold. */
    static final int MAX_BYTES = 1024;

    private final InputStream stream;

    public StdinSecretInput(InputStream stream) {
        this.stream = stream;
    }

    /** @param prompt not shown: nothing is written to the output for a secret that comes from a pipe */
    @Override
    public char[] read(String prompt) {
        byte[] line = new byte[MAX_BYTES];
        try {
            int length = 0;
            int next = stream.read();
            if (next < 0) {
                return null;
            }
            while (next >= 0 && next != '\n') {
                if (length == MAX_BYTES) {
                    throw new FailureException(ErrorCode.SECRET_TOO_LONG);
                }
                line[length++] = (byte) next;
                next = stream.read();
            }
            if (length > 0 && line[length - 1] == '\r') {
                length--;
            }
            return decode(line, length);
        } catch (IOException e) {
            // the text of the exception may hold what was read: only its kind is reported
            throw new FailureException(ErrorCode.STDIN_FAILED, e.getClass().getSimpleName());
        } finally {
            Arrays.fill(line, (byte) 0);
        }
    }

    private static char[] decode(byte[] line, int length) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = CharBuffer.allocate(length);
        try {
            var result = decoder.decode(ByteBuffer.wrap(line, 0, length), chars, true);
            if (result.isUnderflow()) {
                result = decoder.flush(chars);
            }
            if (!result.isUnderflow()) {
                result.throwException();
            }
        } catch (CharacterCodingException e) {
            Arrays.fill(chars.array(), '\0');
            throw new FailureException(ErrorCode.INVALID_ENCODING);
        }
        char[] secret = Arrays.copyOf(chars.array(), chars.position());
        Arrays.fill(chars.array(), '\0');
        return secret;
    }
}
