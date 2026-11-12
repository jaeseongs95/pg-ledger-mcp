package org.pgledger.cli.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.cli.CliException;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.seed.InstallDefaults;
import org.pgledger.cli.seed.PackagedSeed;
import org.springframework.boot.DefaultApplicationArguments;

/** TLS-005, TLS-006, TLS-010, TLS-013, TLS-015: the options of {@code tls-issue} and {@code tls-renew}, refused before anything is read or sent. */
class TlsRequestReaderTest {

    private static final String[] HOSTS = { "--mcp-host=mcp.example.org", "--admin-host=admin.example.org" };

    private final TlsRequestReader reader = new TlsRequestReader(new InstallDefaults(new PackagedSeed()));

    private static DefaultApplicationArguments arguments(String... more) {
        List<String> all = new ArrayList<>(List.of(HOSTS));
        all.addAll(List.of(more));
        return new DefaultApplicationArguments(all.toArray(String[]::new));
    }

    private CliException refused(boolean issue, String... args) {
        try {
            reader.read(new DefaultApplicationArguments(args), issue);
        } catch (CliException e) {
            return e;
        }
        throw new AssertionError("the options were not refused");
    }

    @Test
    @DisplayName("TLS-006 the default certificate authority is the production directory of Let's Encrypt and nothing else is a default of the product")
    void tls006_theDefault() {
        assertThat(TlsOptions.DEFAULT_DIRECTORY).isEqualTo("https://acme-v02.api.letsencrypt.org/directory");
        assertThat(reader.read(arguments(), true).acmeDirectory()).hasToString(TlsOptions.DEFAULT_DIRECTORY);
        assertThat(reader.read(arguments("--acme-directory=https://ca.example.org:14000/dir"), false).acmeDirectory())
                .hasToString("https://ca.example.org:14000/dir");
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://ca.example.org/dir", "https://ca.example.org:14000/dir", "http://127.0.0.1:14000/dir",
            "http://localhost/dir", "http://[::1]:14000/dir", "HTTPS://CA.example.org/dir" })
    @DisplayName("TLS-006 https and http to this host are accepted as the directory URL")
    void tls006_accepted(String url) {
        assertThat(TlsRequestReader.directory(url)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://ca.example.org/dir", "http://192.0.2.1/dir", "ftp://ca.example.org/dir", "file:///etc/x",
            "https://ca.example.org/a b", "https://ca.example.org/%2e%2e/x", "https://user:Secret-Marker-4711@ca.example.org/dir",
            "https://ca.example.org/dir#frag", "https:///dir", "ca.example.org/dir", "" })
    @DisplayName("TLS-006 any other directory URL is INVALID_OPTION_VALUE (exit 2) and the value is in no message")
    void tls006_refused(String url) {
        CliException refused = refused(true, "--mcp-host=mcp.example.org", "--admin-host=admin.example.org", "--acme-directory=" + url);

        assertThat(refused).isInstanceOf(UsageException.class);
        assertThat(refused.code()).isIn(ErrorCode.INVALID_OPTION_VALUE, ErrorCode.MISSING_OPTION_VALUE);
        assertThat(refused.arguments()).isEmpty();
        assertThat(String.valueOf(refused.getMessage())).doesNotContain("Secret-Marker-4711");
    }

    @Test
    @DisplayName("TLS-005 an e-mail address with a space or without an @ is INVALID_OPTION_VALUE; a good one is read by tls-issue only")
    void tls005_theAddress() {
        assertThat(reader.read(arguments("--acme-email=operator@example.org"), true).email()).isEqualTo("operator@example.org");
        for (String bad : List.of("operator example.org", "operator@", "@example.org", "a b@example.org", "a@b c")) {
            assertThat(refused(true, "--mcp-host=mcp.example.org", "--admin-host=admin.example.org", "--acme-email=" + bad).code())
                    .as(bad).isEqualTo(ErrorCode.INVALID_OPTION_VALUE);
        }
        assertThat(reader.read(arguments(), true).agreeToTerms()).isFalse();
        assertThat(reader.read(arguments("--agree-tos"), true).agreeToTerms()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "abc", "3651", "1.5" })
    @DisplayName("TLS-010 --renew-before-days of 0, -1, abc, 3651 or 1.5 is INVALID_OPTION_VALUE (exit 2)")
    void tls010_badThreshold(String days) {
        assertThat(refused(false, "--mcp-host=mcp.example.org", "--admin-host=admin.example.org", "--renew-before-days=" + days).code())
                .isEqualTo(ErrorCode.INVALID_OPTION_VALUE);
    }

    @Test
    @DisplayName("TLS-010 the threshold defaults to 30 days and takes 1 to 3650")
    void tls010_theDefaultThreshold() {
        assertThat(reader.read(arguments(), false).renewBeforeDays()).isEqualTo(30);
        assertThat(reader.read(arguments("--renew-before-days=60"), false).renewBeforeDays()).isEqualTo(60);
        assertThat(reader.read(arguments("--renew-before-days=3650"), false).renewBeforeDays()).isEqualTo(3650);
    }

    static Stream<Arguments> badHosts() {
        return Stream.of(
                Arguments.argumentSet("mcp host missing", List.of("--admin-host=admin.example.org"), ErrorCode.MISSING_ARGUMENT),
                Arguments.argumentSet("admin host missing", List.of("--mcp-host=mcp.example.org"), ErrorCode.MISSING_ARGUMENT),
                Arguments.argumentSet("equal", List.of("--mcp-host=a.example.org", "--admin-host=a.example.org"),
                        ErrorCode.INVALID_DEPLOY_VALUE),
                Arguments.argumentSet("upper case", List.of("--mcp-host=MCP.example.org", "--admin-host=admin.example.org"),
                        ErrorCode.INVALID_DEPLOY_VALUE),
                Arguments.argumentSet("space", List.of("--mcp-host=mcp example.org", "--admin-host=admin.example.org"),
                        ErrorCode.INVALID_DEPLOY_VALUE),
                Arguments.argumentSet("dollar", List.of("--mcp-host=mcp.example.org", "--admin-host=a$b.example.org"),
                        ErrorCode.INVALID_DEPLOY_VALUE),
                Arguments.argumentSet("too long", List.of("--mcp-host=" + "a".repeat(254), "--admin-host=admin.example.org"),
                        ErrorCode.INVALID_DEPLOY_VALUE));
    }

    @ParameterizedTest
    @MethodSource("badHosts")
    @DisplayName("TLS-013 the host names are those of render-deploy: a missing one is MISSING_ARGUMENT, a bad or equal one INVALID_DEPLOY_VALUE naming the option")
    void tls013_theHosts(List<String> args, ErrorCode expected) {
        CliException refused = refused(true, args.toArray(String[]::new));

        assertThat(refused.code()).isEqualTo(expected);
        if (expected == ErrorCode.INVALID_DEPLOY_VALUE) {
            assertThat(refused.arguments()).hasSize(1).first().isIn("mcp-host", "admin-host");
        }
    }

    @Test
    @DisplayName("TLS-015 the web root and the TLS directory are the options when given and the layout of the instance when not; a path that is not plain is INVALID_DEPLOY_VALUE naming the option")
    void tls015_thePaths() {
        TlsRequest given = reader.read(arguments("--webroot=/srv/acme", "--tls-dir=/srv/tls"), true);
        TlsRequest windows = reader.read(arguments("--webroot=C:/acme", "--tls-dir=D:/data/tls"), true);
        TlsRequest named = reader.read(arguments("--name=x"), true);
        TlsRequest defaults = reader.read(arguments(), true);

        assertThat(given.webroot()).isEqualTo(Path.of("/srv/acme"));
        assertThat(given.directory()).isEqualTo(Path.of("/srv/tls"));
        assertThat(windows.webroot()).isEqualTo(Path.of("C:/acme"));
        assertThat(named.webroot()).isEqualTo(Path.of("/var/www/x-acme"));
        assertThat(named.directory()).isEqualTo(Path.of("/etc/x/tls"));
        assertThat(defaults.directory()).isEqualTo(Path.of("/etc/" + new InstallDefaults(new PackagedSeed()).instanceName() + "/tls"));
        for (String bad : List.of("--webroot=/srv/a b", "--webroot=/srv/../etc", "--webroot=relative", "--webroot=/srv/a$b",
                "--tls-dir=/srv/a b", "--tls-dir=/srv/..", "--tls-dir=tls")) {
            CliException refused = refused(true, "--mcp-host=mcp.example.org", "--admin-host=admin.example.org", bad);
            assertThat(refused.code()).as(bad).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(refused.arguments()).containsExactly(bad.substring(2, bad.indexOf('=')));
        }
        assertThat(refused(true, "--mcp-host=mcp.example.org", "--admin-host=admin.example.org", "--name=Bad Name").code())
                .isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
    }
}
