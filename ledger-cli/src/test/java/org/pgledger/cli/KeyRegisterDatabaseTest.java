package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-021 .. CLI-026, CLI-030, CLI-031: {@code key-register} on the real wiring. The application context is the real one
 * (the real {@code SessionKeyService}, repositories and transaction manager; no mock) over an embedded H2 whose schema the
 * real migrator built; the test reads {@code session_key} and {@code owner_audit} afterwards.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class KeyRegisterDatabaseTest {

    private static final String AUTHOR = "claude:session-1";
    private static final String OTHER_AUTHOR = "claude:session-2";
    private static final String USER = "alice";

    private static final String LIVE_KEYS = "SELECT author_id, fingerprint, public_key FROM session_key "
            + "WHERE revoked_at IS NULL ORDER BY id";
    private static final String CLOSED_KEYS = "SELECT author_id, fingerprint FROM session_key "
            + "WHERE revoked_at IS NOT NULL ORDER BY id";
    private static final String AUDIT = "SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;

    @BeforeEach
    void migrate() {
        database = TestLedgerDatabase.migrated();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Outcome register(Cli cli, RecordingOutput output, String author, KeyPair pair, String... options) {
        List<String> line = new ArrayList<>(List.of("key-register", author, PublicKeys.encode(pair.getPublic()),
                PublicKeys.fingerprint(pair.getPublic())));
        line.addAll(List.of(options));
        return cli.withDatabase(database.dataSource()).asProcessUser(USER)
                .run(tmp.resolve("secrets"), null, output, line.toArray(String[]::new));
    }

    private static String fingerprint(KeyPair pair) {
        return PublicKeys.fingerprint(pair.getPublic());
    }

    @Test
    @DisplayName("CLI-021 key-register on the real wiring stores one live session_key row for the author with the fingerprint and the canonical key")
    void storesTheKey(Cli cli) {
        KeyPair pair = pair();
        RecordingOutput output = new RecordingOutput();

        Outcome result = register(cli, output, AUTHOR, pair);

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        List<Map<String, Object>> live = database.rows(LIVE_KEYS);
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().get("author_id")).isEqualTo(AUTHOR);
        assertThat(live.getFirst().get("fingerprint")).isEqualTo(fingerprint(pair));
        assertThat(live.getFirst().get("public_key")).isEqualTo(PublicKeys.encode(pair.getPublic()));
        assertThat(database.rows(CLOSED_KEYS)).isEmpty();
        assertThat(output.info()).contains(AUTHOR).contains(fingerprint(pair));
    }

    @Test
    @DisplayName("CLI-022 the registration is in the owner audit trail with the actor cli:<process user>, the operation KEY_REGISTERED, the author as subject and the fingerprint in the detail")
    void auditsTheRegistration(Cli cli) {
        KeyPair pair = pair();

        Outcome result = register(cli, new RecordingOutput(), AUTHOR, pair);

        assertThat(result.exitCode()).isZero();
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("KEY_REGISTERED");
        assertThat(audit.getFirst().get("subject")).isEqualTo(AUTHOR);
        assertThat((String) audit.getFirst().get("detail")).contains(fingerprint(pair));
    }

    @Test
    @DisplayName("CLI-023 a second, different key for an author with a live key is KEY_REPLACEMENT_NOT_CONFIRMED (exit 1): the message shows the live fingerprint, its registration time and --replace=<that fingerprint>; nothing changes")
    void refusesASilentReplacement(Cli cli, CapturedOutput captured) {
        KeyPair first = pair();
        KeyPair second = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, first).exitCode()).isZero();

        Outcome result = register(cli, new RecordingOutput(), AUTHOR, second);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.KEY_REPLACEMENT_NOT_CONFIRMED);
        List<String> arguments = result.refusal().orElseThrow().arguments();
        assertThat(arguments).hasSize(2);
        assertThat(arguments.get(0)).isEqualTo(fingerprint(first));
        assertThat(Instant.parse(arguments.get(1))).isBefore(Instant.now().plusSeconds(60));
        assertThat(captured.getAll()).contains(fingerprint(first)).contains(arguments.get(1))
                .contains("--replace=" + fingerprint(first));
        List<Map<String, Object>> live = database.rows(LIVE_KEYS);
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().get("fingerprint")).isEqualTo(fingerprint(first));
        assertThat(database.rows(CLOSED_KEYS)).isEmpty();
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-024 --replace=<the live fingerprint> (any letter case) replaces the key: the old row is closed, the new one is live, the audit trail has KEY_REPLACED by cli:<user> naming the replaced fingerprint")
    void replacesWithTheRightFingerprint(Cli cli) {
        KeyPair first = pair();
        KeyPair second = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, first).exitCode()).isZero();
        RecordingOutput output = new RecordingOutput();

        Outcome result = register(cli, output, AUTHOR, second,
                "--replace=" + fingerprint(first).toUpperCase(Locale.ROOT));

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        List<Map<String, Object>> live = database.rows(LIVE_KEYS);
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().get("fingerprint")).isEqualTo(fingerprint(second));
        List<Map<String, Object>> closed = database.rows(CLOSED_KEYS);
        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().get("fingerprint")).isEqualTo(fingerprint(first));
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(2);
        assertThat(audit.get(1).get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.get(1).get("operation")).isEqualTo("KEY_REPLACED");
        assertThat(audit.get(1).get("subject")).isEqualTo(AUTHOR);
        assertThat((String) audit.get(1).get("detail")).contains(fingerprint(second))
                .contains("replaced=" + fingerprint(first));
        assertThat(output.info()).contains(AUTHOR).contains(fingerprint(second)).contains(fingerprint(first));
    }

    @Test
    @DisplayName("CLI-025 --replace with a fingerprint that is not the live key's is REPLACE_FINGERPRINT_STALE (exit 1): the message gives the current fingerprint and its registration time; nothing changes")
    void refusesAStaleFingerprint(Cli cli, CapturedOutput captured) {
        KeyPair first = pair();
        KeyPair second = pair();
        KeyPair unrelated = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, first).exitCode()).isZero();

        Outcome result = register(cli, new RecordingOutput(), AUTHOR, second, "--replace=" + fingerprint(unrelated));

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.REPLACE_FINGERPRINT_STALE);
        List<String> arguments = result.refusal().orElseThrow().arguments();
        assertThat(arguments.get(0)).isEqualTo(fingerprint(first));
        assertThat(Instant.parse(arguments.get(1))).isBefore(Instant.now().plusSeconds(60));
        assertThat(captured.getAll()).contains("--replace=" + fingerprint(first));
        List<Map<String, Object>> live = database.rows(LIVE_KEYS);
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().get("fingerprint")).isEqualTo(fingerprint(first));
        assertThat(database.rows(CLOSED_KEYS)).isEmpty();
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-026 registering the key the author already has again (with or without --replace naming it) succeeds and changes nothing: one live row, one audit row")
    void isIdempotentForTheSameKey(Cli cli) {
        KeyPair pair = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, pair).exitCode()).isZero();

        Outcome again = register(cli, new RecordingOutput(), AUTHOR, pair);
        Outcome againReplacing = register(cli, new RecordingOutput(), AUTHOR, pair, "--replace=" + fingerprint(pair));

        assertThat(again.exitCode()).isZero();
        assertThat(againReplacing.exitCode()).isZero();
        assertThat(database.rows(LIVE_KEYS)).hasSize(1);
        assertThat(database.rows(CLOSED_KEYS)).isEmpty();
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-030 a key that is live for another author is KEY_IN_USE (exit 1); the second author gets no row")
    void refusesAKeyOfAnotherAuthor(Cli cli) {
        KeyPair pair = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, pair).exitCode()).isZero();

        Outcome result = register(cli, new RecordingOutput(), OTHER_AUTHOR, pair);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.KEY_IN_USE);
        List<Map<String, Object>> live = database.rows(LIVE_KEYS);
        assertThat(live).hasSize(1);
        assertThat(live.getFirst().get("author_id")).isEqualTo(AUTHOR);
    }

    @Test
    @DisplayName("CLI-031 an author without the <vendor>:<session> form is INVALID_AUTHOR (exit 1) and registers nothing")
    void refusesAnInvalidAuthor(Cli cli) {
        Outcome result = register(cli, new RecordingOutput(), "Not An Author", pair());

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.INVALID_AUTHOR);
        assertThat(database.rows(LIVE_KEYS)).isEmpty();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    private Outcome revoke(Cli cli, RecordingOutput output, String author, String fingerprint) {
        return cli.withDatabase(database.dataSource()).asProcessUser(USER)
                .run(tmp.resolve("secrets"), null, output, "key-revoke", author, "--fingerprint=" + fingerprint);
    }

    @Test
    @DisplayName("CLI-035 (audit2 N3) key-revoke with the fingerprint of the live key closes it (the row stays, closed) and the owner audit trail has KEY_REVOKED with the actor cli:<process user>; a second revoke is NO_LIVE_KEY; the author can register a key again")
    void revokesTheLiveKey(Cli cli) {
        KeyPair pair = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, pair).exitCode()).isZero();
        RecordingOutput output = new RecordingOutput();

        Outcome result = revoke(cli, output, AUTHOR, fingerprint(pair).toUpperCase(Locale.ROOT));

        assertThat(result.exitCode()).isZero();
        assertThat(database.rows(LIVE_KEYS)).isEmpty();
        List<Map<String, Object>> closed = database.rows(CLOSED_KEYS);
        assertThat(closed).hasSize(1);
        assertThat(closed.getFirst().get("fingerprint")).isEqualTo(fingerprint(pair));
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(2);
        assertThat(audit.get(1).get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.get(1).get("operation")).isEqualTo("KEY_REVOKED");
        assertThat(audit.get(1).get("subject")).isEqualTo(AUTHOR);
        assertThat((String) audit.get(1).get("detail")).contains(fingerprint(pair));
        assertThat(output.info()).contains(AUTHOR).contains(fingerprint(pair));

        Outcome again = revoke(cli, new RecordingOutput(), AUTHOR, fingerprint(pair));
        assertThat(again.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(again.code()).contains(ErrorCode.NO_LIVE_KEY);
        assertThat(register(cli, new RecordingOutput(), AUTHOR, pair()).exitCode()).isZero();
    }

    @Test
    @DisplayName("CLI-035 (audit2 N3) key-revoke with another fingerprint is REVOKE_FINGERPRINT_MISMATCH (exit 1) showing the fingerprint of the live key; the key stays live and nothing is audited")
    void refusesAnotherFingerprintForTheRevoke(Cli cli) {
        KeyPair pair = pair();
        assertThat(register(cli, new RecordingOutput(), AUTHOR, pair).exitCode()).isZero();

        Outcome result = revoke(cli, new RecordingOutput(), AUTHOR, fingerprint(pair()));

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.REVOKE_FINGERPRINT_MISMATCH);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly(fingerprint(pair));
        assertThat(database.rows(LIVE_KEYS)).hasSize(1);
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-036 (audit2 S5) the owner role user:owner has no session key: key-register for it is INVALID_AUTHOR (exit 1) and stores nothing")
    void refusesTheOwnerRole(Cli cli) {
        Outcome result = register(cli, new RecordingOutput(), "user:owner", pair());

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.INVALID_AUTHOR);
        assertThat(database.rows(LIVE_KEYS)).isEmpty();
        assertThat(database.rows(AUDIT)).isEmpty();
    }
}
