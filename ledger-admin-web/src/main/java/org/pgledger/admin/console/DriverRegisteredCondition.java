package org.pgledger.admin.console;

import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when the console can have its database: the {@code embedded} profile (tests; the database comes from
 * {@code spring.datasource.*}), or a registered JDBC driver that the product's own loader accepts (jar present,
 * digest as registered, class loadable). Otherwise the application starts in setup mode, and the reason is logged
 * (it names paths and codes, never a secret).
 *
 * <p>Why a condition of ours (principle 2.1): the registration is read from the environment, which Spring Boot has
 * already filled from {@code spring.config.import} when conditions are evaluated; Boot's own conditions
 * ({@code @ConditionalOnProperty}, {@code @ConditionalOnBean}) cannot ask whether the registered jar still matches
 * its digest. {@link Condition} is the Spring Framework contract for exactly this decision.
 */
public final class DriverRegisteredCondition implements Condition {

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegisteredCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (context.getEnvironment().matchesProfiles("embedded")) {
            return true;
        }
        try {
            DriverRegistration registration = Binder.get(context.getEnvironment())
                    .bind("ledger.jdbc.driver", DriverRegistration.class)
                    .orElseGet(() -> new DriverRegistration(null, null, null, null));
            DriverLoader.load(registration).close();
            return true;
        } catch (InfrastructureException | BindException e) {
            LOG.warn("no usable JDBC driver is registered: the admin starts in setup mode (login and driver "
                    + "registration only)", e);
            return false;
        }
    }
}
