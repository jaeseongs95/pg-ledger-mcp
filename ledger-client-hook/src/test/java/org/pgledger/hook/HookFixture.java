package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.pgledger.hook.keys.SessionKeyStore;
import tools.jackson.databind.JsonNode;

/**
 * Per-test fixture: runs the hook in process with the test's own key folder and a fixed clock ({@link #NOW}, moved
 * with {@link #at}). The signed attestation of a run is checked with {@link #stamp}: parsed, claims compared, signature
 * verified with the public key of the key folder.
 */
final class HookFixture {

    /** The host session id used by the tests; it exercises the whole allowed character set. */
    static final String HOST_ID = "parent-1234_ab.cd";

    /** The time of the injected clock until a test moves it. */
    static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

    private final JsonCodec codec = JsonCodec.create();
    private final Path keyDirectory;
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    HookFixture(Path keyDirectory) {
        this.keyDirectory = keyDirectory;
    }

    /** The key folder of this test (not created until the first key). */
    Path keyDirectory() {
        return keyDirectory;
    }

    /** Moves the injected clock; the next run issues its attestation at this time. */
    HookFixture at(Instant instant) {
        this.clock = Clock.fixed(instant, ZoneOffset.UTC);
        return this;
    }

    Run run(String[] args, byte[] stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // a new store and a new command for every run: like a new process, nothing is kept between two runs
        int exit = new HookCli(codec, clock, new SessionKeyStore(keyDirectory))
                .run(args, new ByteArrayInputStream(stdin), out, err);
        return new Run(exit, out.toByteArray(), err.toByteArray());
    }

    Run run(HostScenario host, byte[] stdin) {
        return run(new String[] {host.client().argument()}, stdin);
    }

    /** Runs the hook and requires the clean channels of HK-016: exit 0, nothing on stderr. */
    Run runClean(HostScenario host, byte[] stdin) {
        Run run = run(host, stdin);
        assertThat(run.err()).as("stderr").isEmpty();
        assertThat(run.exit()).as("exit code").isZero();
        return run;
    }

    /** Checks the attestation of a direct call of the standard host id that was answered by {@code run}. */
    Attestations.Stamp stamp(HostScenario host, String op, JsonNode sent, Run run) {
        return stamp(host, HOST_ID, op, sent, run);
    }

    Attestations.Stamp stamp(HostScenario host, String hostId, String op, JsonNode sent, Run run) {
        return stampCarrier(host.author(hostId), op, sent, host.stampedPart(run));
    }

    /** Checks the attestation inside {@code carrier}: the stamped arguments of a call whose own arguments were {@code sent}. */
    Attestations.Stamp stampCarrier(String author, String op, JsonNode sent, JsonNode carrier) {
        return Attestations.verify(keyDirectory, Attestations.Window.at(clock.instant()), author, op, sent, carrier);
    }
}
