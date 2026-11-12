package org.pgledger.cli.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.CliException;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.secret.ProcessUser;

/**
 * TLS-010 .. TLS-012: when {@code tls-renew} orders and when it does nothing. The authority is a mock of the client
 * ({@link AcmeClient}): what is tested is the decision, and that a certificate that is not due costs no request at all. The
 * certificates in place are made by the test double of a certificate authority ({@link StubCertificateAuthority}) for a clock
 * that stands still.
 */
class TlsRenewTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final List<String> HOSTS = List.of("mcp.example.org", "admin.example.org");
    private static final ProcessUser USER = fileSystem -> fileSystem.getUserPrincipalLookupService()
            .lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());

    @TempDir
    Path tmp;

    private Path tlsDir;
    private StubCertificateAuthority authority;
    private TlsFiles files;
    private AcmeClient client;
    private TlsIssuer issuer;

    @BeforeEach
    void start() throws Exception {
        tlsDir = Files.createDirectories(tmp.resolve("tls"));
        authority = new StubCertificateAuthority(tmp.resolve("webroot"), CLOCK);
        files = new TlsFiles(USER);
        client = mock(AcmeClient.class);
        issuer = new TlsIssuer(client, files, CLOCK);
        // an order of the mock gives a certificate for the hosts that is valid for 90 days
        when(client.obtain(any(), any(), eq(true), any())).thenReturn(
                new AcmeClient.Issued(authority.newChain(HOSTS, 90), files.newKeyPair()));
    }

    private TlsRequest request(int renewBeforeDays) {
        return new TlsRequest(HOSTS, tmp.resolve("webroot"), tlsDir, URI.create("https://ca.example.org/dir"), null, false,
                renewBeforeDays);
    }

    private void inPlace(List<String> names, long days, boolean withAccount) throws Exception {
        Files.writeString(files.chain(tlsDir), authority.newChain(names, days), StandardCharsets.US_ASCII);
        if (withAccount) {
            files.writeAccountKey(tlsDir, files.newKeyPair());
        }
    }

    static Stream<Arguments> thresholds() {
        return Stream.of(
                Arguments.argumentSet("29 days, threshold 30: due", 29, 30, true),
                Arguments.argumentSet("31 days, threshold 30: not due", 31, 30, false),
                Arguments.argumentSet("30 days, threshold 30: due (at the threshold is within it)", 30, 30, true),
                Arguments.argumentSet("59 days, threshold 60: due", 59, 60, true),
                Arguments.argumentSet("61 days, threshold 60: not due", 61, 60, false),
                Arguments.argumentSet("1 day, threshold 1: due", 1, 1, true),
                Arguments.argumentSet("2 days, threshold 1: not due", 2, 1, false));
    }

    @ParameterizedTest
    @MethodSource("thresholds")
    @DisplayName("TLS-010 a certificate that expires within the threshold is renewed, and one that does not costs no request at all and is reported as not due")
    void tls010_theThreshold(long days, int threshold, boolean due) throws Exception {
        inPlace(HOSTS, days, true);

        TlsIssuer.Outcome outcome = issuer.renew(request(threshold));

        if (due) {
            assertThat(outcome.result()).isEqualTo(TlsIssuer.Result.ISSUED);
            verify(client).obtain(any(), any(), eq(true), any());
            assertThat(outcome.notAfter()).isEqualTo(NOW.plusSeconds(90L * 24 * 3600));
        } else {
            assertThat(outcome.result()).isEqualTo(TlsIssuer.Result.NOT_DUE);
            assertThat(outcome.notAfter()).isEqualTo(NOW.plusSeconds(days * 24 * 3600));
            verifyNoInteractions(client);
        }
    }

    static Stream<Arguments> otherNames() {
        return Stream.of(
                Arguments.argumentSet("only the MCP host", List.of("mcp.example.org")),
                Arguments.argumentSet("the two hosts and a third", List.of("mcp.example.org", "admin.example.org", "x.example.org")),
                Arguments.argumentSet("two other hosts", List.of("a.example.org", "b.example.org")));
    }

    @ParameterizedTest
    @MethodSource("otherNames")
    @DisplayName("TLS-011 a certificate that does not name exactly the two hosts is renewed although it is far from expiry; one with exactly the two names is not")
    void tls011_theNames(List<String> names) throws Exception {
        inPlace(names, 80, true);

        assertThat(issuer.renew(request(30)).result()).isEqualTo(TlsIssuer.Result.ISSUED);
        verify(client).obtain(any(), any(), eq(true), any());
    }

    @Test
    @DisplayName("TLS-011 the names of the certificate are compared as a set, in lower case, in any order")
    void tls011_theOrderDoesNotMatter() throws Exception {
        inPlace(List.of("admin.example.org", "mcp.example.org"), 80, true);

        assertThat(issuer.renew(request(30)).result()).isEqualTo(TlsIssuer.Result.NOT_DUE);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("TLS-012 no certificate, or a certificate and no account key: TLS_NO_CERTIFICATE and TLS_NO_ACCOUNT, with no request; a renewal never creates an account")
    void tls012_nothingToRenew() throws Exception {
        assertThat(refusal().code()).isEqualTo(ErrorCode.TLS_NO_CERTIFICATE);
        inPlace(HOSTS, 5, false);
        assertThat(refusal().code()).isEqualTo(ErrorCode.TLS_NO_ACCOUNT);
        Files.writeString(files.chain(tlsDir), "this is no certificate", StandardCharsets.US_ASCII);
        files.writeAccountKey(tlsDir, files.newKeyPair());
        assertThat(refusal().code()).as("an unreadable certificate is no certificate").isEqualTo(ErrorCode.TLS_NO_CERTIFICATE);

        verifyNoInteractions(client);
    }

    private CliException refusal() {
        try {
            issuer.renew(request(30));
        } catch (CliException refused) {
            return refused;
        }
        throw new AssertionError("the renewal was not refused");
    }
}
