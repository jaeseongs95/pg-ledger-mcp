package org.pgledger.mcp.security;

import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Registers the size check for every request: after the security chain (a request that is not allowed is not read). */
@Configuration(proxyBeanMethods = false)
public class McpWebConfiguration {

    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(LedgerMcpProperties properties,
            MessageSourceAccessor modelMessages,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(
                new RequestSizeFilter(properties, modelMessages, errors));
        registration.addUrlPatterns("/*");
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
