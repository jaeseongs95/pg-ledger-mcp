package org.pgledger.mcp.security;

import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the size check for every request of the MVC dispatcher. */
@Configuration(proxyBeanMethods = false)
public class McpWebConfiguration implements WebMvcConfigurer {

    private final LedgerMcpProperties properties;
    private final MessageSourceAccessor messages;

    public McpWebConfiguration(LedgerMcpProperties properties, MessageSourceAccessor modelMessages) {
        this.properties = properties;
        this.messages = modelMessages;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestSizeInterceptor(properties, messages));
    }
}
