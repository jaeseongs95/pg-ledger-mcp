package org.pgledger.cli.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.secret.ProcessUser;
import org.pgledger.cli.secret.SecretProtection;
import org.pgledger.cli.support.RequiresOwnerOnlyStorage;
import org.pgledger.cli.support.RequiresPosixPermissions;

/** TLS-008, TLS-009: the atomic replacement of the files and their permissions (the code of the secret store decides the protection of a key). */
class TlsFilesTest {

    private static final ProcessUser USER = fileSystem -> fileSystem.getUserPrincipalLookupService()
            .lookupPrincipalByName(ProcessHandle.current().info().user().orElseThrow());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> list = Files.list(directory)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("TLS-008 a replacement that fails half way leaves the old file byte for byte and no temporary file; one of a missing file leaves no file; one that works gives the new bytes, written under a temporary name in the directory of the target")
    void tls008_theReplacementIsAtomic() throws IOException {
        Path target = tmp.resolve("fullchain.pem");
        Files.writeString(target, "OLD CONTENT", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> AtomicFile.replaceShared(target, out -> {
            out.write("NEW HALF".getBytes(StandardCharsets.US_ASCII));
            throw new IOException("the disk failed");
        })).isInstanceOf(IOException.class).hasMessage("the disk failed");
        Path missing = tmp.resolve("missing.pem");
        assertThatThrownBy(() -> AtomicFile.replaceShared(missing, out -> {
            out.write(1);
            throw new IOException("the disk failed");
        })).isInstanceOf(IOException.class);

        assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("OLD CONTENT");
        assertThat(names(tmp)).containsExactly("fullchain.pem");

        List<String> during = new java.util.ArrayList<>();
        AtomicFile.replaceShared(target, out -> {
            during.addAll(names(tmp));
            out.write("NEW CONTENT".getBytes(StandardCharsets.US_ASCII));
        });
        assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("NEW CONTENT");
        assertThat(during).as("while writing, a temporary file is next to the target").anyMatch(name -> name.startsWith(AtomicFile.TEMPORARY_PREFIX));
        assertThat(names(tmp)).containsExactly("fullchain.pem");
    }

    @Test
    @DisplayName("TLS-008 a staged file is not in place until it is committed, and closed without a commit it is gone")
    void tls008_stagedFiles() throws IOException {
        Path target = tmp.resolve("privkey.pem");
        Files.writeString(target, "OLD KEY", StandardCharsets.US_ASCII);

        try (AtomicFile.Staged staged = AtomicFile.stageShared(target, out -> out.write("NEW KEY".getBytes(StandardCharsets.US_ASCII)))) {
            assertThat(staged).isNotNull();
            assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("OLD KEY");
            assertThat(names(tmp)).hasSize(2);
        }

        assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("OLD KEY");
        assertThat(names(tmp)).containsExactly("privkey.pem");
    }

    private Path issueIntoTmp() throws Exception {
        Path webroot = tmp.resolve("webroot");
        Path tls = tmp.resolve("tls");
        try (StubCertificateAuthority authority = new StubCertificateAuthority(webroot, CLOCK)) {
            TlsFiles files = new TlsFiles(USER);
            TlsIssuer issuer = new TlsIssuer(new AcmeClient(files, Mockito.mock(CliOutput.class), Mockito.mock(CliMessages.class)),
                    files, CLOCK);
            issuer.issue(new TlsRequest(List.of("mcp.example.org", "admin.example.org"), webroot, tls,
                    URI.create(authority.directoryUrl()), null, true, 30));
            if (tls.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(authority.tokenModes()).as("the token file and its directory, read by the web server")
                        .containsOnly("rw-r--r-- rwxr-xr-x");
            }
        }
        return tls;
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("TLS-009 (POSIX) the keys are rw-------, the chain rw-r--r--, the token file rw-r--r-- in a directory rwxr-xr-x")
    void tls009_theModes() throws Exception {
        Path tls = issueIntoTmp();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls.resolve("privkey.pem")))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls.resolve("account.key")))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls.resolve("fullchain.pem")))).isEqualTo("rw-r--r--");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tls))).as("the TLS directory is made owner-only")
                .isEqualTo("rwx------");
    }

    @Test
    @RequiresOwnerOnlyStorage
    @DisplayName("TLS-009 (POSIX modes or ACLs) the protection of the secret store holds for both keys: the owner alone has access")
    void tls009_theKeysAreOwnerOnly() throws Exception {
        Path tls = issueIntoTmp();
        SecretProtection protection = SecretProtection.of(tls, USER);

        protection.requireOwnerOnlyAccess(tls.resolve("privkey.pem"));
        protection.requireOwnerOnlyAccess(tls.resolve("account.key"));
    }

    @Test
    @DisplayName("TLS-009 an account key that is written can be read back as the same key pair")
    void tls009_theAccountKeyRoundTrips() throws Exception {
        TlsFiles files = new TlsFiles(USER);
        Path tls = Files.createDirectories(tmp.resolve("tls"));
        java.security.KeyPair keys = files.newKeyPair();

        files.writeAccountKey(tls, keys);
        java.security.KeyPair read = files.readAccountKey(tls);

        assertThat(read.getPrivate().getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        assertThat(read.getPublic().getEncoded()).isEqualTo(keys.getPublic().getEncoded());
        assertThat(Files.readString(files.accountKey(tls))).contains("-----BEGIN PRIVATE KEY-----", "-----BEGIN PUBLIC KEY-----");
    }

    @Test
    @DisplayName("TLS-003 only a token of the base64url alphabet is written below the web root; anything else writes nothing")
    void tls003_aTokenIsAFileNameOnlyWhenItIsPlain() throws IOException {
        TlsFiles files = new TlsFiles(USER);
        Path webroot = Files.createDirectories(tmp.resolve("webroot"));

        assertThat(files.publishToken(webroot, "abcDEF012_-", "abcDEF012_-.thumb")).hasValueSatisfying(
                file -> assertThat(file).isEqualTo(webroot.resolve(".well-known").resolve("acme-challenge").resolve("abcDEF012_-")));
        for (String bad : List.of("../../x", "a/b", "a\\b", "", "x".repeat(129), "a.b", "a b", "..", ".")) {
            assertThat(files.publishToken(webroot, bad, "x")).as("'%s'", bad).isEmpty();
        }
        assertThat(files.publishToken(webroot, null, "x")).isEmpty();
        try (Stream<Path> walk = Files.walk(tmp)) {
            assertThat(walk.filter(Files::isRegularFile).map(path -> path.getFileName().toString())).containsExactly("abcDEF012_-");
        }
    }
}
