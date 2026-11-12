package org.pgledger.cli.token;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one random source of the tokens: the platform's default {@link SecureRandom}. */
@Configuration(proxyBeanMethods = false)
class TokenConfiguration {

    /** A {@code @Bean} method because {@link SecureRandom} is a JDK class; {@link TokenGenerator} is a component that takes it. */
    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
