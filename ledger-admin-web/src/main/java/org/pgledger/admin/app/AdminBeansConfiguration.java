package org.pgledger.admin.app;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/** The beans both modes need: the UTC clock, the language of the pages and the mode itself. */
@Configuration(proxyBeanMethods = false)
public class AdminBeansConfiguration {

    /** The one source of "now"; all time is UTC. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The time source of the lock after failed attempts: the monotonic clock of the JVM ({@code System.nanoTime}), not
     * the UTC {@link Clock}, so setting the system time back or forward cannot end or extend a lock.
     */
    @Bean
    Ticker lockoutTicker() {
        return Ticker.systemTicker();
    }

    /**
     * The one place that decides the language of a request (Spring MVC looks the bean up by this name): the
     * {@code Accept-Language} header, Korean or English, English when the browser asks for neither.
     */
    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.KOREAN, Locale.ENGLISH));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Bean
    MessageSourceAccessor messages(MessageSource messageSource) {
        return new MessageSourceAccessor(messageSource);
    }

    /** The console exists exactly when the ledger database does. */
    @Bean
    AdminMode adminMode(ObjectProvider<DataSource> dataSource) {
        return dataSource.getIfAvailable() == null ? AdminMode.SETUP : AdminMode.CONSOLE;
    }
}
