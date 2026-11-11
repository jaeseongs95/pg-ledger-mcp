package org.pgledger.cli.token;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one random source of the tokens: the platform's default {@link SecureRandom}. */
@Configuration(proxyBeanMethods = false)
class TokenConfiguration {

    @Bean
    TokenGenerator tokenGenerator() {
        return new TokenGenerator(new SecureRandom());
    }
}
