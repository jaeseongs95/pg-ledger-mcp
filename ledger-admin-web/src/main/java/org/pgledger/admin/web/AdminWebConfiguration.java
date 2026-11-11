package org.pgledger.admin.web;

import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Registers the size check for every request: after the security chain (a request that is not allowed is not read). */
@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration {

    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(LedgerAdminProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(
                new RequestSizeFilter(properties, errors));
        registration.addUrlPatterns("/*");
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
