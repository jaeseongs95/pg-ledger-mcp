package org.pgledger.cli.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;

/**
 * TLS-001, TLS-004, TLS-010, TLS-014: {@code tls-issue} and {@code tls-renew} as the application runs them (the real context, the
 * real option parsing and messages), against the test double of a certificate authority ({@link StubCertificateAuthority}).
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class TlsCommandTest {

    private static final String[] HOSTS = { "--mcp-host=mcp.example.org", "--admin-host=admin.example.org" };

    @TempDir
    Path tmp;

    private Outcome run(Cli cli, RecordingOutput output, StubCertificateAuthority authority, String command, String... more) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(command));
        args.addAll(java.util.List.of(HOSTS));
        args.add("--webroot=" + tmp.resolve("webroot").toString().replace('\\', '/'));
        args.add("--tls-dir=" + tmp.resolve("tls").toString().replace('\\', '/'));
        args.add("--acme-directory=" + authority.directoryUrl());
        args.addAll(java.util.List.of(more));
        return cli.run(tmp.resolve("secrets"), null, output, args.toArray(String[]::new));
    }

    @Test
    @DisplayName("TLS-001 tls-issue as the application runs it: exit 0, the two PEM files and the account key are there, the output names the files and the expiry and prints no key, key authorization or account key (TLS-014)")
    void tls001_issueEndToEnd(Cli cli) throws Exception {
        try (StubCertificateAuthority authority = new StubCertificateAuthority(tmp.resolve("webroot"), Clock.systemUTC())) {
            RecordingOutput output = new RecordingOutput();

            Outcome result = run(cli, output, authority, "tls-issue", "--agree-tos", "--acme-email=operator@example.org");

            assertThat(result.failure()).isNull();
            assertThat(result.exitCode()).isZero();
            Path tls = tmp.resolve("tls");
            assertThat(tls.resolve("fullchain.pem")).isRegularFile();
            assertThat(tls.resolve("privkey.pem")).isRegularFile();
            assertThat(tls.resolve("account.key")).isRegularFile();
            assertThat(output.info()).contains("mcp.example.org, admin.example.org", "fullchain.pem", "privkey.pem", "is valid until");
            String secrets = Files.readString(tls.resolve("privkey.pem"), StandardCharsets.US_ASCII)
                    + Files.readString(tls.resolve("account.key"), StandardCharsets.US_ASCII);
            assertThat(output.info()).doesNotContain("PRIVATE KEY", "PUBLIC KEY");
            for (String line : secrets.lines().filter(l -> !l.startsWith("-----")).toList()) {
                assertThat(output.info()).doesNotContain(line);
            }
            for (String keyAuthorization : authority.expectedKeyAuthorizations()) {
                assertThat(output.info()).doesNotContain(keyAuthorization);
            }
            assertThat(authority.accountBodies()).hasSize(1).allSatisfy(body -> assertThat(body).containsEntry("termsOfServiceAgreed", true));
        }
    }

    @Test
    @DisplayName("TLS-004 tls-issue without --agree-tos and without an account is TLS_TERMS_NOT_AGREED (exit 2) and the authority sees no request")
    void tls004_termsAreNeeded(Cli cli) throws Exception {
        try (StubCertificateAuthority authority = new StubCertificateAuthority(tmp.resolve("webroot"), Clock.systemUTC())) {
            Outcome result = run(cli, new RecordingOutput(), authority, "tls-issue");

            assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(result.code()).contains(ErrorCode.TLS_TERMS_NOT_AGREED);
            assertThat(authority.requestCount()).isZero();
            assertThat(tmp.resolve("tls")).doesNotExist();
        }
    }

    @Test
    @DisplayName("TLS-010 tls-renew right after tls-issue says that the certificate is not due and sends no request; with a threshold above its validity it renews")
    void tls010_renewIsNotDueThenDue(Cli cli) throws Exception {
        try (StubCertificateAuthority authority = new StubCertificateAuthority(tmp.resolve("webroot"), Clock.systemUTC())) {
            assertThat(run(cli, new RecordingOutput(), authority, "tls-issue", "--agree-tos").exitCode()).isZero();
            int requests = authority.requestCount();
            RecordingOutput notDue = new RecordingOutput();

            Outcome result = run(cli, notDue, authority, "tls-renew");

            assertThat(result.exitCode()).isZero();
            assertThat(notDue.info()).contains("not due for renewal", "Nothing was ordered");
            assertThat(authority.requestCount()).isEqualTo(requests);
            RecordingOutput due = new RecordingOutput();

            Outcome renewed = run(cli, due, authority, "tls-renew", "--renew-before-days=100");

            assertThat(renewed.exitCode()).isZero();
            assertThat(due.info()).contains("Obtained the certificate");
            assertThat(authority.requestCount()).isGreaterThan(requests);
            assertThat(authority.accountsCreated()).as("the renewal used the account of the issue").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TLS-012 tls-renew without a certificate is TLS_NO_CERTIFICATE (exit 1); the option --agree-tos is not an option of tls-renew")
    void tls012_nothingToRenew(Cli cli) throws Exception {
        try (StubCertificateAuthority authority = new StubCertificateAuthority(tmp.resolve("webroot"), Clock.systemUTC())) {
            Outcome none = run(cli, new RecordingOutput(), authority, "tls-renew");
            Outcome unknown = run(cli, new RecordingOutput(), authority, "tls-renew", "--agree-tos");

            assertThat(none.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(none.code()).contains(ErrorCode.TLS_NO_CERTIFICATE);
            assertThat(unknown.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(authority.requestCount()).isZero();
        }
    }

    @Test
    @DisplayName("TLS-013 a bad host name is refused before anything is sent: INVALID_DEPLOY_VALUE (exit 2)")
    void tls013_badHost(Cli cli) throws Exception {
        try (StubCertificateAuthority authority = new StubCertificateAuthority(tmp.resolve("webroot"), Clock.systemUTC())) {
            Outcome result = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), CliCommand.TLS_ISSUE.cliName(),
                    "--mcp-host=MCP.example.org", "--admin-host=admin.example.org", "--agree-tos",
                    "--acme-directory=" + authority.directoryUrl());

            assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
            assertThat(result.code()).contains(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(authority.requestCount()).isZero();
        }
    }
}
