package org.pgledger.mcp.security;

import jakarta.servlet.DispatcherType;
import java.util.Locale;
import java.util.Set;
import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * The transport security of the server, all of it Spring Security: the Host header must be on the allow list (the
 * firewall refuses any other with 403, before anything else runs: the guard against DNS rebinding), a request with
 * an Origin header must come from an allowed origin, and everything but the health endpoint needs the bearer token
 * of the agents (401 without or with a wrong one). The server keeps no session of its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class McpSecurityConfiguration {

    /**
     * A {@code @Bean} method because the firewall is a Spring Security type that the framework picks up by type and that
     * is configured with the allowed hosts of this server; it has no constructor the container could fill.
     */
    @Bean
    HttpFirewall httpFirewall(LedgerMcpProperties properties) {
        Set<String> hosts = Set.copyOf(properties.allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT)).toList());
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHostnames(host -> hosts.contains(host.toLowerCase(Locale.ROOT)));
        return firewall;
    }

    /**
     * A {@code @Bean} method because the handler is a Spring Security class that the framework picks up by type; the
     * status it answers with (403, for a Host that is not allowed) is the choice made here.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler(HttpStatus.FORBIDDEN.value());
    }

    @Bean
    SecurityFilterChain mcpSecurity(HttpSecurity http, AgentTokenProvider tokens, TransportOriginAuthorization origin)
            throws Exception {
        AuthenticationManager agents = new ProviderManager(tokens);
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyRequest().access(AuthorizationManagers.allOf(origin,
                                AuthenticatedAuthorizationManager.authenticated())))
                .oauth2ResourceServer(resource -> resource.authenticationManagerResolver(request -> agents))
                .build();
    }
}
