package org.pgledger.mcp.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.mcp.LedgerMcpServerApplication;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * A test class of the MCP server on a fresh installation: the schema is built, the common codes of the schema files
 * are there, but no defect category has been added yet. Everything else is as in {@link McpServerTest}; the context
 * is a second one because its configuration differs.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ContextConfiguration(initializers = TestSchemaInitializer.class)
@SpringBootTest(classes = LedgerMcpServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {McpTestSettings.QUIET_BANNER, McpTestSettings.QUIET_LOG, McpTestSettings.NO_JMX,
            McpTestSettings.TOKEN_PROPERTY, McpTestSettings.POLL_PROPERTY, McpTestSettings.ORIGIN_PROPERTY})
@ActiveProfiles("embedded")
@Import(McpTestSchema.class)
@ExtendWith(McpExtension.class)
public @interface McpFreshInstallationTest {
}
