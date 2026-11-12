package org.pgledger.mcp.config;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Settings of the MCP SDK server that Spring AI has no property for. The SDK validating the arguments against the
 * schema is turned off because the tool binding of this server validates them itself and answers with the error
 * token VALIDATION_FAILED and one issue per problem, which the text of the SDK cannot give.
 */
@Configuration(proxyBeanMethods = false)
public class McpServerConfiguration {

    /**
     * Replaces the customizer of Spring AI (which only runs the handlers on the request thread, as a servlet
     * container needs) by one that does the same and also turns the SDK validation off. A {@code @Bean} method because
     * the customizer is the one-method interface of Spring AI, which the starter looks up by type; it is made from a
     * lambda, not from a class.
     */
    @Bean
    @Primary
    McpSyncServerCustomizer servletServerWithToolBindingValidation() {
        return server -> server.immediateExecution(true).validateToolInputs(false);
    }
}
