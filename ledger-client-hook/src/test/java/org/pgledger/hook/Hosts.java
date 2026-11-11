package org.pgledger.hook;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** The three hosts and the case sources built on them; one case is one invocation of a parameterized test. */
final class Hosts {

    /** The ten write tools of the external contract, written out here and not taken from the product. */
    static final List<String> WRITE_OPS = List.of(
            "save", "transition", "record_fix", "record_case", "link", "correct", "batch", "review", "review_correct", "whoami");

    static final List<HostScenario> ALL = List.of(new ClaudeHost(), new CodexHost(), AntigravityHost.INSTANCE);

    private Hosts() {}

    static Stream<HostScenario> all() {
        return ALL.stream();
    }

    /** Every host with every write tool. */
    static Stream<Arguments> withWriteOps() {
        return cross(WRITE_OPS);
    }

    /** Every host with every given value: one case per pair. */
    static <T> Stream<Arguments> cross(Collection<? extends T> values) {
        return ALL.stream().flatMap(host -> values.stream()
                .map(value -> Arguments.argumentSet(host + " " + value, host, value)));
    }
}
