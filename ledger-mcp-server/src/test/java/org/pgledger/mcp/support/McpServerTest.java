package org.pgledger.mcp.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.mcp.LedgerMcpServerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A test class of the MCP server: the real server in a Spring Boot context on a random port (Streamable HTTP at
 * {@code /mcp}), on an embedded H2 database that the real schema files have built. The tests talk to it with the
 * MCP Java SDK client ({@link McpSession}); nothing is mocked. Spring's test context cache shares the one context
 * between the test classes that use the same properties; every test works in a project of its own
 * ({@link Scenario}), so they do not collide.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = LedgerMcpServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {McpTestSettings.URL, McpTestSettings.POOL, McpTestSettings.UTC_SESSION,
            McpTestSettings.QUIET_BANNER, McpTestSettings.QUIET_LOG, McpTestSettings.NO_JMX,
            McpTestSettings.TOKEN_PROPERTY, McpTestSettings.POLL_PROPERTY})
@ActiveProfiles("embedded")
@Import(McpTestInfrastructure.class)
@ExtendWith(McpExtension.class)
public @interface McpServerTest {
}
