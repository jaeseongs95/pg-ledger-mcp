package org.pgledger.cli.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.pgledger.cli.CliException;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.secret.ProcessUser;

/**
 * TLS-001 .. TLS-005, TLS-007: the ACME flow of {@code tls-issue} against {@link StubCertificateAuthority}, a test double of a
 * certificate authority that speaks the endpoints of RFC 8555 the client uses. The real authority is not part of these tests.
 */
class TlsFlowTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final List<String> HOSTS = List.of("mcp.example.org", "admin.example.org");
    private static final ProcessUser USER = fileSystem -> fileSystem.getUserPrincipalLookupService()
            .lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());

    @TempDir
    Path tmp;

    private Path webroot;
    private Path tlsDir;
    private StubCertificateAuthority authority;
    private TlsFiles files;
    private TlsIssuer issuer;

    @BeforeEach
    void start() throws Exception {
        webroot = Files.createDirectories(tmp.resolve("webroot"));
        tlsDir = tmp.resolve("tls");
        authority = new StubCertificateAuthority(webroot, CLOCK);
        files = new TlsFiles(USER);
        issuer = new TlsIssuer(new AcmeClient(files, Mockito.mock(CliOutput.class), Mockito.mock(CliMessages.class)), files, CLOCK);
    }

    @AfterEach
    void stop() {
        authority.close();
    }

    private TlsRequest request(String email, boolean agree) {
        return new TlsRequest(HOSTS, webroot, tlsDir, URI.create(authority.directoryUrl()), email, agree, 30);
    }

    private static X509Certificate certificate(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))).iterator().next();
    }

    private static CliException refusal(Runnable action) {
        try {
            action.run();
        } catch (CliException refused) {
            return refused;
        }
        throw new AssertionError("the command was not refused");
    }

    @Test
    @DisplayName("TLS-001 tls-issue speaks RFC 8555 to the authority in the order directory, account (jwk, terms agreed; the nonce comes from the Replay-Nonce of the answers), order for exactly the two names, both authorizations, both challenges, finalize, certificate; the chain and the key are put in place and belong together")
    void tls001_theFlow() throws Exception {
        TlsIssuer.Outcome outcome = issuer.issue(request(null, true));

        assertThat(outcome.result()).isEqualTo(TlsIssuer.Result.ISSUED);
        assertThat(outcome.notAfter()).isEqualTo(NOW.plus(java.time.Duration.ofDays(90)));
        List<String> kinds = authority.requests().stream().map(r -> r.replaceAll("/\\d+$", "/N")).toList();
        assertThat(kinds).containsSubsequence("GET /directory", "POST /new-acct", "POST /new-order", "POST /authz/N",
                "POST /chall/N", "POST /finalize/N", "POST /cert/N");
        assertThat(kinds.stream().filter("POST /authz/N"::equals).count()).isGreaterThanOrEqualTo(2);
        assertThat(kinds.stream().filter("POST /chall/N"::equals).count()).isEqualTo(2);
        assertThat(authority.newAccountHeaders()).hasSize(1).allSatisfy(header -> {
            assertThat(header).containsEntry("alg", "ES256").containsKey("jwk").doesNotContainKey("kid");
        });
        assertThat(authority.accountBodies()).hasSize(1).allSatisfy(body -> assertThat(body).containsEntry("termsOfServiceAgreed", true));
        assertThat(authority.orderedNames()).containsExactly(HOSTS);

        X509Certificate leaf = certificate(Files.readString(files.chain(tlsDir)));
        assertThat(files.names(leaf)).containsExactlyInAnyOrderElementsOf(HOSTS);
        assertThat(Files.readString(files.chain(tlsDir)).split("-----BEGIN CERTIFICATE-----", -1)).as("the leaf and its issuer")
                .hasSize(3);
        // the key belongs to the certificate: what it signs, the public key of the certificate verifies
        String keyText = Files.readString(files.privateKey(tlsDir));
        assertThat(keyText).startsWith("-----BEGIN PRIVATE KEY-----").endsWith("-----END PRIVATE KEY-----\n");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder()
                .decode(keyText.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""))));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(new byte[] {1, 2, 3});
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(leaf.getPublicKey());
        verifier.update(new byte[] {1, 2, 3});
        assertThat(verifier.verify(signature)).as("privkey.pem is the key of fullchain.pem").isTrue();
        assertThat(files.hasAccountKey(tlsDir)).isTrue();
    }

    @Test
    @DisplayName("TLS-002 the token file is below <webroot>/.well-known/acme-challenge/ and holds exactly the key authorization at the moment the authority validates; no token file is left afterwards")
    void tls002_theTokenFile() throws Exception {
        issuer.issue(request(null, true));

        assertThat(authority.tokenFileExistedAtValidation()).containsExactly(true, true);
        assertThat(authority.tokenFileContents()).hasSize(2).containsExactlyInAnyOrderElementsOf(authority.expectedKeyAuthorizations());
        assertThat(authority.tokenFileContents()).allSatisfy(content -> assertThat(content).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}"));
        try (Stream<Path> walk = Files.walk(webroot.resolve(".well-known"))) {
            assertThat(walk.filter(Files::isRegularFile)).as("no token file is left").isEmpty();
        }
    }

    static Stream<Arguments> badTokens() {
        return Stream.of("../../escape", "a/b", "a\\b", "", "x".repeat(300), "tok.en", "to ken", "tok%2fen", "tök")
                .map(token -> Arguments.argumentSet("token '" + token.substring(0, Math.min(8, token.length())) + "'", token));
    }

    @ParameterizedTest
    @MethodSource("badTokens")
    @DisplayName("TLS-003 a token that is not made of the base64url alphabet is refused (TLS_CHALLENGE_TOKEN) before anything is written, and the challenge is never triggered")
    void tls003_aTokenWithAPathIsRefused(String token) throws IOException {
        authority.offerToken(token);

        CliException refused = refusal(() -> issuer.issue(request(null, true)));

        assertThat(refused.code()).isEqualTo(ErrorCode.TLS_CHALLENGE_TOKEN);
        assertThat(authority.requests()).noneMatch(r -> r.startsWith("POST /chall/"));
        try (Stream<Path> walk = Files.walk(tmp)) {
            assertThat(walk.filter(Files::isRegularFile).map(tmp::relativize).map(Path::toString).map(s -> s.replace('\\', '/')))
                    .as("nothing but the account key was written").containsExactly("tls/account.key");
        }
    }

    @Test
    @DisplayName("TLS-004 without an account and without --agree-tos nothing is sent and no key is made; with an account key the terms option is not needed")
    void tls004_theTerms() throws Exception {
        assertThat(refusal(() -> issuer.issue(request(null, false)))).isInstanceOf(UsageException.class)
                .extracting(CliException::code).isEqualTo(ErrorCode.TLS_TERMS_NOT_AGREED);
        assertThat(authority.requestCount()).isZero();
        assertThat(files.hasAccountKey(tlsDir)).isFalse();

        issuer.issue(request(null, true));
        int before = authority.requestCount();
        issuer.issue(request(null, false));

        assertThat(authority.requestCount()).isGreaterThan(before);
    }

    @Test
    @DisplayName("TLS-005 the contact is sent only when an address is given; a second run uses the same account key and creates no second account")
    void tls005_theAccount() throws Exception {
        issuer.issue(request("operator@example.org", true));
        byte[] key = Files.readAllBytes(files.accountKey(tlsDir));
        issuer.issue(request("operator@example.org", false));

        assertThat(authority.accountBodies()).hasSize(1).allSatisfy(body -> assertThat(body)
                .containsEntry("contact", List.of("mailto:operator@example.org")));
        assertThat(Files.readAllBytes(files.accountKey(tlsDir))).isEqualTo(key);
        assertThat(authority.accountsCreated()).isEqualTo(1);
    }

    @Test
    @DisplayName("TLS-005 without an address the new account has no contact")
    void tls005_noContact() throws Exception {
        issuer.issue(request(null, true));

        assertThat(authority.accountBodies()).hasSize(1).allSatisfy(body -> assertThat(body).doesNotContainKey("contact"));
    }

    private enum Failure {
        AUTHORIZATION, FINALIZE, DOWNLOAD, NONCE, NAMES
    }

    @ParameterizedTest
    @ValueSource(strings = { "AUTHORIZATION", "FINALIZE", "DOWNLOAD", "NONCE", "NAMES" })
    @DisplayName("TLS-007 a failure of the authority (invalid authorization, finalize 500, download 500, a nonce refused again and again, a certificate for other names) is TLS_ISSUE_FAILED, the old files are byte for byte what they were, and no temporary or token file is left")
    void tls007_theOldFilesStay(String name) throws Exception {
        Files.createDirectories(tlsDir);
        issuer.issue(request(null, true));
        byte[] chain = Files.readAllBytes(files.chain(tlsDir));
        byte[] key = Files.readAllBytes(files.privateKey(tlsDir));
        switch (Failure.valueOf(name)) {
            case AUTHORIZATION -> authority.invalidateAuthorizations();
            case FINALIZE -> authority.failFinalize(500);
            case DOWNLOAD -> authority.failCertificateDownload(500);
            case NONCE -> authority.refuseNonces(1000);
            case NAMES -> authority.issueForNames(List.of("other.example.org"));
        }

        CliException refused = refusal(() -> issuer.issue(request(null, false)));

        assertThat(refused.code()).isEqualTo(ErrorCode.TLS_ISSUE_FAILED);
        assertThat(Files.readAllBytes(files.chain(tlsDir))).isEqualTo(chain);
        assertThat(Files.readAllBytes(files.privateKey(tlsDir))).isEqualTo(key);
        try (Stream<Path> walk = Files.walk(tmp)) {
            assertThat(walk.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
                    .noneMatch(file -> file.startsWith(AtomicFile.TEMPORARY_PREFIX));
        }
        try (Stream<Path> walk = Files.walk(webroot)) {
            assertThat(walk.filter(Files::isRegularFile)).isEmpty();
        }
        assertThat(refused.arguments()).as("no key and no body in the refusal").noneMatch(argument -> argument.contains("PRIVATE KEY"));
    }

    @Test
    @DisplayName("TLS-007 a failure of the renewal (invalid authorization) leaves the old certificate in place too")
    void tls007_renewalFails() throws Exception {
        issuer.issue(request(null, true));
        Files.writeString(files.chain(tlsDir), authority.newChain(HOSTS, 10));
        byte[] chain = Files.readAllBytes(files.chain(tlsDir));
        authority.invalidateAuthorizations();

        CliException refused = refusal(() -> issuer.renew(request(null, false)));

        assertThat(refused.code()).isEqualTo(ErrorCode.TLS_ISSUE_FAILED);
        assertThat(Files.readAllBytes(files.chain(tlsDir))).isEqualTo(chain);
    }
}
