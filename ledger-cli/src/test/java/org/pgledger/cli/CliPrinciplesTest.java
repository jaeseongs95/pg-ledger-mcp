package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.cli.key.SessionKeyRegistry;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.database.LedgerDatabaseConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

/**
 * CLI-PRN-09, CLI-PRN-10 and CLI-PRN-12 (plan 2.1, 2.5, 3): the clock of the tool, the cause of an option that is no number,
 * and the options of the key commands read by {@code CliOptions}.
 */
@ExtendWith(CliExtension.class)
class CliPrinciplesTest {

    @TempDir
    Path tmp;

    /** What the registry was asked to store. */
    private static final class RecordingRegistry implements SessionKeyRegistry {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void register(String author, String publicKey) {
            calls.add("register " + author);
        }

        @Override
        public void registerReplacing(String author, String publicKey, String replacedFingerprint) {
            calls.add("replace " + author + " " + replacedFingerprint);
        }

        @Override
        public void revoke(String author, String fingerprint) {
            calls.add("revoke " + author + " " + fingerprint);
        }
    }

    @Test
    @DisplayName("CLI-PRN-09 the clock configuration of the tool has no condition and makes exactly one Clock, in UTC; the ledger database configuration does not make a second one")
    void oneUtcClock() {
        new ApplicationContextRunner().withUserConfiguration(CliClockConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
        // a second clock would make every service that takes one ambiguous (or silently take the bean named like the parameter)
        assertThat(org.pgledger.cli.database.CliLedgerConfiguration.class.getAnnotation(Import.class).value())
                .as("the database configuration of the tool does not import the clock of the database module")
                .doesNotContain(LedgerDatabaseConfiguration.class);
    }

    @Test
    @DisplayName("CLI-PRN-10 code-set --sort=abc is INVALID_OPTION_VALUE (exit 2), the number format failure is the cause of the refusal, and the value is in no argument of it")
    void aSortThatIsNoNumberKeepsItsCause(Cli cli) {
        Outcome result = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), "code-set", "DEFECT_CATEGORY", "BUG",
                "ko-name", "--sort=abc-4711");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        CliException refusal = result.refusal().orElseThrow();
        assertThat(refusal.code()).isEqualTo(ErrorCode.INVALID_OPTION_VALUE);
        assertThat(refusal.getCause()).isInstanceOf(NumberFormatException.class);
        assertThat(refusal.arguments()).isEmpty();
        assertThat(refusal.getMessage()).doesNotContain("4711");
    }

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @DisplayName("CLI-PRN-12 the options of key-register and key-revoke are read by CliOptions: a blank or doubled value, and a missing required one, are usage errors; a fingerprint is stripped and lower-cased on its way to the registry")
    void keyOptions(Cli cli) {
        RecordingRegistry registry = new RecordingRegistry();
        KeyPair pair = pair();
        String key = PublicKeys.encode(pair.getPublic());
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        String author = "claude:session-1";

        Outcome blank = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-register", author, key,
                fingerprint, "--replace=");
        Outcome twice = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-register", author, key,
                fingerprint, "--replace=a", "--replace=b");
        Outcome missing = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-revoke", author);
        Outcome revokeBlank = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-revoke", author,
                "--fingerprint=");
        Outcome revokeTwice = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-revoke", author,
                "--fingerprint=a", "--fingerprint=b");

        assertThat(blank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(twice.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(missing.code()).contains(ErrorCode.MISSING_ARGUMENT);
        assertThat(revokeBlank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(revokeTwice.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        for (Outcome usage : List.of(blank, twice, missing, revokeBlank, revokeTwice)) {
            assertThat(usage.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        }
        assertThat(registry.calls).isEmpty();

        Outcome revoked = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-revoke", author,
                "--fingerprint= ABCD1234 ");
        Outcome replaced = cli.run(tmp.resolve("secrets"), null, new RecordingOutput(), registry, "key-register", author, key,
                fingerprint, "--replace= EF01 ");

        assertThat(revoked.failure()).isNull();
        assertThat(replaced.failure()).isNull();
        assertThat(registry.calls).containsExactly("revoke " + author + " abcd1234", "replace " + author + " ef01");
    }
}
