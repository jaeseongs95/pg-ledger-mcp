package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Per-test fixture: runs the hook in process. */
final class HookFixture {

    /** The host session id used by the tests; it exercises the whole allowed character set. */
    static final String HOST_ID = "parent-1234_ab.cd";

    private final JsonCodec codec = JsonCodec.create();

    Run run(String[] args, byte[] stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new HookCli(codec).run(args, new ByteArrayInputStream(stdin), out, err);
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
}
