package org.pgledger.mcp.reflection;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.jackson.databind.json.JsonMapper;

/**
 * W7-TB-01: {@code ToolBinding.argumentsDigest} does not swallow the failure of the digest. Arguments that are no JSON have no
 * digest (the call goes on, the gate refuses it for the missing digest), and the reason is kept at debug level with the
 * exception as its cause, for whoever looks at why the attestation did not match.
 */
class ToolBindingDigestLogTest {

    /** A tool that takes the attestation and one free value, as the write tools take theirs. */
    public static class Attested {

        @McpTool(name = "probe_attested", description = "Answers ok.")
        public String call(@McpToolParam(required = false) String _hostAttestation,
                @McpToolParam(required = false) Object extra) {
            return "ok";
        }
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(ToolBinding.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level before;

    @BeforeEach
    void capture() {
        events.start();
        logger.addAppender(events);
        before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void release() {
        logger.detachAppender(events);
        logger.setLevel(before);
        events.stop();
    }

    private ToolBinding binding() {
        List<ToolBinding> bindings = new ToolBindings(JsonMapper.builder().build()).bind(new Attested());
        assertThat(bindings).singleElement().extracting(ToolBinding::name).isEqualTo("probe_attested");
        return bindings.getFirst();
    }

    @Test
    @DisplayName("W7-TB-01 arguments that are no JSON: the call goes on and exactly one debug event names the tool and carries the IllegalArgumentException as its cause")
    void aFailedDigestIsLoggedAtDebug() {
        Object answer = binding().call(Map.of("_hostAttestation", "pgla2.x", "extra", Double.NaN));

        assertThat(answer).isEqualTo("ok");
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("probe_attested");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalArgumentException.class.getName());
        });
    }

    @Test
    @DisplayName("W7-TB-01 arguments that are JSON, and a call without attestation: no event")
    void nothingIsLoggedWhenThereIsNothingToSay() {
        binding().call(Map.of("_hostAttestation", "pgla2.x", "extra", Map.of("key", "value")));
        binding().call(Map.of("extra", Double.NaN));

        assertThat(events.list).isEmpty();
    }
}
