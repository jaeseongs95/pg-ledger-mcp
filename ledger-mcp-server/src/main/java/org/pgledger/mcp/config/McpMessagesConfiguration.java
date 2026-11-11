package org.pgledger.mcp.config;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.MessageSourceAccessor;

/**
 * Every text the MCP server shows to a model (error messages, tool and argument descriptions) comes from the
 * message bundles and is English: the audience is a model, so the text does not follow the caller's language.
 */
@Configuration(proxyBeanMethods = false)
public class McpMessagesConfiguration {

    /** The language of all model-facing text. */
    private static final Locale MODEL_LANGUAGE = Locale.ENGLISH;

    @Bean
    MessageSourceAccessor modelMessages(MessageSource messageSource) {
        return new MessageSourceAccessor(messageSource, MODEL_LANGUAGE);
    }
}
