package org.pgledger.admin.web;

import java.util.List;
import org.pgledger.admin.security.OwnerActorResolver;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * What the web layer of the admin adds to Spring MVC: the resolver of the owner identity that the controllers take as an
 * argument, and the place of the size check in the filter chain.
 */
@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration implements WebMvcConfigurer {

    private final OwnerActorResolver owners;

    public AdminWebConfiguration(OwnerActorResolver owners) {
        this.owners = owners;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(owners);
    }

    /**
     * A {@code @Bean} method because a registration bean is the Spring Boot type that puts a filter into the servlet
     * container at a URL pattern and an order, and it is built from values (the pattern and the order), not injected: the
     * filter itself is the component {@link RequestSizeFilter}. It runs after the security chain, so a request that is not
     * allowed is not read.
     */
    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilterRegistration(RequestSizeFilter filter) {
        FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
