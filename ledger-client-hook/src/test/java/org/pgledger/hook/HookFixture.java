package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Per-test fixture: runs the hook in process and builds the expected stamped arguments. */
final class HookFixture {

    /** The host session id used by the tests; it exercises the whole allowed character set. */
    static final String HOST_ID = "parent-1234_ab.cd";
    static final String RESERVED_KEY = "_hostAttestation";

    private final JsonCodec codec = JsonCodec.create();

    Run run(String[] args, byte[] stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            int exit = new HookCli(codec).run(args, new ByteArrayInputStream(stdin), out, err);
            return new Run(exit, out.toByteArray(), err.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /** The arguments as the hook must forward them: the model's arguments plus the attestation, nothing else. */
    JsonNode stamped(JsonNode body, String attestation) {
        ObjectNode copy = ((ObjectNode) body).deepCopy();
        copy.put(RESERVED_KEY, attestation);
        return copy;
    }

    /** A tool input given as a Java value (record, list ...), turned into the JSON the model would have sent. */
    JsonNode body(Object value) {
        return Json.MAPPER.valueToTree(value);
    }

    String attestation(HostScenario host, String hostId) {
        return host.vendor() + ":" + hostId;
    }
}
