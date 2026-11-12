package org.pgledger.admin.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.pgledger.admin.app.AdminMode;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The security of the admin web, all of it Spring Security in one filter chain: the Host allow list (the firewall refuses
 * any other Host with 403, before anything else runs: the guard against DNS rebinding), the admin token (login form and
 * {@code Authorization: Bearer}, one provider), OpenID Connect {@code oauth2Login} when a client registration is
 * configured, the CSRF token with the session cookie of Spring Boot ({@code SameSite=Strict}), a new session id after
 * the sign-in (fixation protection), the lock after failed attempts and the headers (CSP and the rest).
 *
 * <p>In setup mode only the login, the logout and the driver page are open; every other route is refused (503
 * DRIVER_NOT_CONFIGURED for a signed-in owner) before anything is read from a database that does not exist.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AdminSecurityConfiguration {

    /**
     * No inline script or style: the pages load their script and style from the application itself. Nobody may frame the
     * pages, and forms post only back to the application.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
            + "form-action 'self'; base-uri 'none'; object-src 'none'";

    /**
     * A {@code @Bean} method because the firewall is a Spring Security type that the framework picks up by type and that
     * is configured with the allowed hosts of this application ({@link AllowedHosts}); it has no constructor the
     * container could fill.
     */
    @Bean
    HttpFirewall httpFirewall(AllowedHosts hosts) {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHostnames(hosts::allows);
        return firewall;
    }

    /**
     * A request the firewall refuses: 403 DNS_REBINDING_BLOCKED when the Host is the reason, 400 for any other.
     * A {@code @Bean} method because the handler is the one-method interface of Spring Security, which the framework picks
     * up by type, made here from the resolver that hands the failure to the error advice.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(AllowedHosts hosts,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, rejected) -> resolver.resolveException(request, response, null,
                hosts.allows(request.getServerName()) ? new ErrorResponseException(HttpStatus.BAD_REQUEST)
                        : new AdminException(AdminCode.DNS_REBINDING_BLOCKED, List.of()));
    }

    @Bean
    SecurityFilterChain adminSecurity(HttpSecurity http, AdminMode mode, AdminTokenProvider tokens,
            SecurityFailures failures, ObjectProvider<ClientRegistrationRepository> clients,
            OwnerOidcUserService owners, ServerProperties server) throws Exception {
        AuthenticationManager bearer = new ProviderManager(tokens);
        http
                .authorizeHttpRequests(requests -> {
                    requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                            .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                            .requestMatchers("/login", "/oauth2/authorization/**", "/login/oauth2/code/**")
                            .permitAll();
                    if (mode == AdminMode.SETUP) {
                        requests.requestMatchers("/driver").authenticated().anyRequest().denyAll();
                    } else {
                        requests.anyRequest().authenticated();
                    }
                })
                .formLogin(form -> form.loginPage("/login").passwordParameter("token")
                        .defaultSuccessUrl(mode == AdminMode.SETUP ? "/driver" : "/").failureHandler(failures))
                .logout(logout -> {
                    logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").invalidateHttpSession(true);
                    String cookie = server.getServlet().getSession().getCookie().getName();
                    if (cookie != null) {
                        logout.deleteCookies(cookie);
                    }
                })
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(errors -> errors.accessDeniedHandler(failures)
                        .defaultAuthenticationEntryPointFor(failures,
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .oauth2ResourceServer(resource -> resource.authenticationManagerResolver(request -> bearer)
                        .authenticationEntryPoint(failures))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=()")));
        ClientRegistrationRepository registrations = clients.getIfAvailable();
        if (registrations != null) {
            // PKCE for every client, also a confidential one (Spring Security: OAuth2AuthorizationRequestCustomizers.withPkce)
            DefaultOAuth2AuthorizationRequestResolver authorizations = new DefaultOAuth2AuthorizationRequestResolver(
                    registrations, "/oauth2/authorization");
            authorizations.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(login -> login.loginPage("/login")
                    .defaultSuccessUrl(mode == AdminMode.SETUP ? "/driver" : "/")
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizations))
                    .userInfoEndpoint(user -> user.oidcUserService(owners)).failureHandler(failures));
        }
        return http.build();
    }
}
