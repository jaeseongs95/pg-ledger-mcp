package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * SEC-023: {@code --stdin} gives a secret without a terminal, the way {@code docker login --password-stdin} does
 * (https://docs.docker.com/reference/cli/docker/login/#password-stdin): the first line of standard input, nothing from the
 * command line or the environment. The reader takes that line only, so the next line stays for the next command.
 */
class StdinSecretInputTest {

    private static char[] read(String text) {
        return new StdinSecretInput(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).read("never shown");
    }

    @ParameterizedTest
    @ValueSource(strings = { "secret-value\n", "secret-value\r\n", "secret-value" })
    @DisplayName("SEC-023 the first line is the secret: the line end (LF or CRLF) is not part of it, and a last line without a line end is accepted")
    void sec023_theFirstLineWithoutItsLineEnd(String text) {
        assertThat(read(text)).containsExactly("secret-value".toCharArray());
    }

    @Test
    @DisplayName("SEC-023 only the first line is read: the second line is still in the stream for the next command, so two secrets can follow each other on one standard input")
    void sec023_nothingBeyondTheFirstLineIsConsumed() throws IOException {
        InputStream stream = new ByteArrayInputStream("first\nsecond\nthird".getBytes(StandardCharsets.UTF_8));
        StdinSecretInput input = new StdinSecretInput(stream);

        assertThat(input.read("p")).containsExactly("first".toCharArray());
        assertThat(stream.available()).isEqualTo("second\nthird".length());
        assertThat(input.read("p")).containsExactly("second".toCharArray());
    }

    @Test
    @DisplayName("SEC-023 UTF-8 text, spaces inside and a CR inside the line are kept; only the end of the line is cut")
    void sec023_textIsKeptAsItIs() {
        assertThat(read("päss 한글 😀\n")).containsExactly("päss 한글 😀".toCharArray());
        assertThat(read("a\rb\n")).containsExactly("a\rb".toCharArray());
    }

    @Test
    @DisplayName("SEC-023 an empty line is an empty secret (the command refuses it as EMPTY_SECRET) and the end of the input without a byte is null")
    void sec023_emptyAndEnd() {
        assertThat(read("\nnext")).isEmpty();
        assertThat(read("\r\n")).isEmpty();
        assertThat(read("")).isNull();
    }

    @Test
    @DisplayName("SEC-023 a line of more than 1024 bytes is refused (SECRET_TOO_LONG) and the refusal does not hold any of it")
    void sec023_aLongLineIsRefused() {
        String longest = "x".repeat(1024);
        assertThat(read(longest + "\n")).hasSize(1024);

        assertThatThrownBy(() -> read(longest + "y\n")).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.SECRET_TOO_LONG);
            assertThat(e.arguments()).isEmpty();
            assertThat(String.valueOf(e.getMessage())).doesNotContain("xxx");
        });
    }

    @Test
    @DisplayName("SEC-023 bytes that are not UTF-8 are refused (INVALID_ENCODING) without the text")
    void sec023_invalidUtf8IsRefused() {
        byte[] bytes = { 'a', (byte) 0xC3, (byte) 0x28, '\n' };

        assertThatThrownBy(() -> new StdinSecretInput(new ByteArrayInputStream(bytes)).read("p"))
                .isInstanceOfSatisfying(FailureException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ENCODING);
                    assertThat(e.arguments()).isEmpty();
                });
    }

    @Test
    @DisplayName("SEC-023 a stream that fails is STDIN_FAILED naming the kind of failure and never the text")
    void sec023_aFailingStreamIsRefused() {
        InputStream broken = new InputStream() {

            @Override
            public int read() throws IOException {
                throw new IOException("the secret-looking text of the failure");
            }
        };

        assertThatThrownBy(() -> new StdinSecretInput(broken).read("p")).isInstanceOfSatisfying(FailureException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.STDIN_FAILED);
            assertThat(e.arguments()).containsExactly("IOException");
            assertThat(Arrays.toString(e.arguments().toArray())).doesNotContain("secret-looking");
        });
    }
}
