package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.ToolNames;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * REH4-MCP-01: the start of the server registers every tool once. The catalog registers the tools with its own definitions;
 * before this unit Spring AI had registered the annotated tools on the server too, so the catalog replaced each of them and the SDK
 * logged {@code Replace existing Tool} as a WARN at every start. The context of this class is its own (the property below gives it a
 * key that no other test class has), and the appender is on the logger of the SDK before the first bean exists ({@link Capture}).
 */
@McpServerTest
@TestPropertySource(properties = "ledger.test.context=tool-registration-log")
@Import(ToolRegistrationLogTest.Capture.class)
class ToolRegistrationLogTest {

    private static final Logger SDK = (Logger) LoggerFactory.getLogger("io.modelcontextprotocol");
    private static final ListAppender<ILoggingEvent> EVENTS = new ListAppender<>();
    /** What the capture test writes itself; the other test leaves it out. */
    private static final String PROBE = "probe of REH4-MCP-01";

    /**
     * Puts the appender on the logger once Boot has set up the logging of the context (it resets the logging system when the
     * context starts, which drops an appender added earlier) and before the first bean is made: a static
     * {@code BeanFactoryPostProcessor} runs at that point.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {

        @Bean
        static BeanFactoryPostProcessor sdkLogCapture() {
            return beanFactory -> {
                EVENTS.start();
                SDK.addAppender(EVENTS);
            };
        }
    }

    @AfterAll
    static void release() {
        SDK.detachAppender(EVENTS);
        EVENTS.stop();
    }

    @Test
    @DisplayName("REH4-MCP-01 starting the server logs no 'Replace existing Tool' and no other WARN of the MCP SDK, the level of its logger is as it was, and the 22 tools are those of the catalog")
    void theToolsAreRegisteredOnce(McpSession session) {
        List<Tool> tools = session.tools();

        List<String> logged = EVENTS.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(text -> !text.contains(PROBE)).toList();
        assertThat(EVENTS.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage).filter(text -> !text.contains(PROBE)))
                .as("the WARN events of the SDK while the server started").isEmpty();
        assertThat(logged).noneMatch(text -> text.contains("Replace existing Tool"));
        assertThat(SDK.getLevel()).as("the level of the logger is not lowered to hide anything").isNull();
        assertThat(tools).extracting(Tool::name).hasSize(22).containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(tools).allSatisfy(tool -> {
            SchemaView input = SchemaView.of(session.mapper(), tool.inputSchema());
            assertThat(input.objects()).as(tool.name()).allSatisfy(object -> assertThat(object.closed()).isTrue());
            assertThat(tool.description()).as(tool.name()).isNotBlank();
        });
    }

    @Test
    @DisplayName("REH4-MCP-01 the capture works: a WARN of the SDK logger that a test writes is seen by the appender (so an empty list above means no event, not no capture)")
    void theCaptureSeesAWarn() {
        LoggerFactory.getLogger("io.modelcontextprotocol.server.McpAsyncServer").warn(PROBE);

        assertThat(EVENTS.list.stream().map(ILoggingEvent::getFormattedMessage)).contains(PROBE);
    }
}
