package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-017 .. CLI-020 and CLI-029: the owner registers the public key of a session with {@code key-register}; the command
 * line is checked here against a recording stand-in of the registry (the database wiring is {@code KeyRegisterDatabaseTest}).
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class })
class KeyRegisterCommandTest {

    private static final String AUTHOR = "claude:session-1";

    @TempDir
    Path tmp;

    /** What the registry was asked to store. */
    private static final class RecordingRegistry implements SessionKeyRegistry {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void register(String author, String publicKey) {
            calls.add("register " + author + " " + publicKey);
        }

        @Override
        public void registerReplacing(String author, String publicKey, String replacedFingerprint) {
            calls.add("replace " + author + " " + publicKey + " " + replacedFingerprint);
        }
    }

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Path secrets() {
        return tmp.resolve("secrets");
    }

    @Test
    @DisplayName("CLI-017 key-register with the key and the fingerprint the session showed registers the canonical key for the author (no replacement) and prints the author and the fingerprint")
    void registers(Cli cli) {
        KeyPair pair = pair();
        String key = PublicKeys.encode(pair.getPublic());
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        RecordingRegistry registry = new RecordingRegistry();
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(secrets(), null, output, registry, "key-register", AUTHOR, " " + key + " ",
                fingerprint.toUpperCase(Locale.ROOT));

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(registry.calls).containsExactly("register " + AUTHOR + " " + key);
        assertThat(output.info()).contains(AUTHOR).contains(fingerprint);
    }

    @Test
    @DisplayName("CLI-017 key-register with --replace=<fingerprint> asks the registry to replace exactly that live key (any letter case) and prints both fingerprints")
    void registersReplacing(Cli cli) {
        KeyPair pair = pair();
        String key = PublicKeys.encode(pair.getPublic());
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        String replaced = PublicKeys.fingerprint(pair().getPublic());
        RecordingRegistry registry = new RecordingRegistry();
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(secrets(), null, output, registry, "key-register", AUTHOR, key, fingerprint,
                "--replace=" + replaced.toUpperCase(Locale.ROOT));

        assertThat(result.exitCode()).isZero();
        assertThat(registry.calls).containsExactly("replace " + AUTHOR + " " + key + " " + replaced);
        assertThat(output.info()).contains(AUTHOR).contains(fingerprint).contains(replaced);
    }

    @Test
    @DisplayName("CLI-018 a fingerprint that is not the one of the key is FINGERPRINT_MISMATCH (exit 1), nothing is registered, and the message gives the real fingerprint to compare")
    void refusesAnotherFingerprint(Cli cli, CapturedOutput captured) {
        KeyPair pair = pair();
        RecordingRegistry registry = new RecordingRegistry();

        Outcome result = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR,
                PublicKeys.encode(pair.getPublic()), PublicKeys.fingerprint(pair().getPublic()));

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.FINGERPRINT_MISMATCH);
        assertThat(registry.calls).isEmpty();
        assertThat(captured.getAll()).contains(PublicKeys.fingerprint(pair.getPublic()));
    }

    @Test
    @DisplayName("CLI-019 a text that is not an Ed25519 public key (a private key, text) is INVALID_PUBLIC_KEY (exit 1) and registers nothing")
    void refusesAnotherKind(Cli cli) {
        RecordingRegistry registry = new RecordingRegistry();
        String privateKey = java.util.Base64.getEncoder().encodeToString(pair().getPrivate().getEncoded());
        for (String bad : List.of(privateKey, "not a key")) {
            Outcome result = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, bad,
                    "0".repeat(64));
            assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.INVALID_PUBLIC_KEY);
        }
        assertThat(registry.calls).isEmpty();
    }

    @Test
    @DisplayName("CLI-020 missing or surplus arguments are a usage error (exit 2); with no driver registered there is no registry and the command refuses with DRIVER_NOT_CONFIGURED naming driver-register (exit 1)")
    void usageAndNoRegistry(Cli cli, CapturedOutput captured) {
        KeyPair pair = pair();
        String key = PublicKeys.encode(pair.getPublic());
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());

        Outcome none = cli.run(secrets(), null, new RecordingOutput(), "key-register");
        Outcome two = cli.run(secrets(), null, new RecordingOutput(), "key-register", AUTHOR, key);
        Outcome four = cli.run(secrets(), null, new RecordingOutput(), "key-register", AUTHOR, key, fingerprint, "x");
        Outcome unconnected = cli.run(secrets(), null, new RecordingOutput(), "key-register", AUTHOR, key,
                fingerprint);

        assertThat(none.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(none.code()).contains(ErrorCode.MISSING_ARGUMENT);
        assertThat(two.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(four.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(four.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(unconnected.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(unconnected.code()).contains(ErrorCode.DRIVER_NOT_CONFIGURED);
        assertThat(captured.getAll()).contains("driver-register");
    }

    @Test
    @DisplayName("CLI-029 --replace without a value or with a blank or a second value, an unknown single-word option and a surplus operand are usage errors (exit 2) and register nothing; dotted Spring options pass")
    void optionsAreChecked(Cli cli) {
        KeyPair pair = pair();
        String key = PublicKeys.encode(pair.getPublic());
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        String other = PublicKeys.fingerprint(pair().getPublic());
        RecordingRegistry registry = new RecordingRegistry();

        Outcome noValue = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, key,
                fingerprint, "--replace");
        Outcome blank = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, key,
                fingerprint, "--replace=");
        Outcome twice = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, key,
                fingerprint, "--replace=" + other, "--replace=" + fingerprint);
        Outcome unknown = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, key,
                fingerprint, "--force");
        Outcome separateWord = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, key,
                fingerprint, "--replace", other);
        Outcome dotted = cli.run(secrets(), null, new RecordingOutput(), registry, "key-register", AUTHOR, key,
                fingerprint, "--spring.main.log-startup-info=false");

        assertThat(noValue.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(noValue.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(blank.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(blank.code()).contains(ErrorCode.MISSING_OPTION_VALUE);
        assertThat(twice.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(twice.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(unknown.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(unknown.code()).contains(ErrorCode.UNKNOWN_OPTION);
        assertThat(separateWord.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(separateWord.code()).contains(ErrorCode.TOO_MANY_ARGUMENTS);
        assertThat(registry.calls).as("only the dotted Spring option was a valid command line").hasSize(1);
        assertThat(dotted.exitCode()).isZero();
    }
}
