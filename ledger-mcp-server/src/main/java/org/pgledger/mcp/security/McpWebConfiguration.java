package org.pgledger.mcp.security;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the size check for every request: after the security chain (a request that is not allowed is not read). */
@Configuration(proxyBeanMethods = false)
public class McpWebConfiguration {

    /**
     * A {@code @Bean} method because a registration bean is the Spring Boot type that puts a filter into the servlet
     * container at a URL pattern and an order, and it is built from values (the pattern and the order), not injected: the
     * filter itself is the component {@link RequestSizeFilter}.
     */
    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilterRegistration(RequestSizeFilter filter) {
        FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
