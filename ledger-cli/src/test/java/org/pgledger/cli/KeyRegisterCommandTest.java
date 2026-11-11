package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** CLI-017 .. CLI-020: the owner registers the public key of a session with {@code key-register}. */
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
            calls.add(author + " " + publicKey);
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
    @DisplayName("CLI-017 key-register with the key and the fingerprint the session showed registers the canonical key for the author and prints the author and the fingerprint")
    void registers(Cli cli) {
        KeyPair pair = pair();
        String key = PublicKeys.encode(pair.getPublic());
        String fingerprint = PublicKeys.fingerprint(pair.getPublic());
        RecordingRegistry registry = new RecordingRegistry();
        RecordingOutput output = new RecordingOutput();

        Outcome result = cli.run(secrets(), null, output, registry, "key-register", AUTHOR, " " + key + " ",
                fingerprint.toUpperCase(java.util.Locale.ROOT));

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(registry.calls).containsExactly(AUTHOR + " " + key);
        assertThat(output.info()).contains(AUTHOR).contains(fingerprint);
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
    @DisplayName("CLI-020 missing or surplus arguments are a usage error (exit 2); a tool without a connection to the ledger refuses with REGISTRY_NOT_CONFIGURED (exit 1)")
    void usageAndNoRegistry(Cli cli) {
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
        assertThat(unconnected.code()).contains(ErrorCode.REGISTRY_NOT_CONFIGURED);
    }
}
