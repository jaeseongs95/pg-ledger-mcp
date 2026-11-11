package org.pgledger.cli.database;

import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when the CLI can have its database: the {@code embedded} profile (tests; the test brings its own DataSource), or
 * a registered JDBC driver that the product's own loader accepts (jar present, digest as registered, class loadable).
 * Otherwise none of the database beans exists, so {@code secret-set} and {@code driver-register} run on a machine that has
 * no JDBC driver, and a command that needs the database is refused with DRIVER_NOT_CONFIGURED.
 *
 * <p>Why a condition of ours (principle 2.1): the registration is read from the environment, which Spring Boot has already
 * filled from {@code spring.config.import} when conditions are evaluated; Boot's own conditions
 * ({@code @ConditionalOnProperty}, {@code @ConditionalOnBean}) cannot ask whether the registered jar still matches its
 * digest. {@link Condition} is the Spring Framework contract for exactly this decision
 * (https://docs.spring.io/spring-framework/reference/core/beans/java/composing-configuration-classes.html). It is the
 * same decision as the condition of the admin web; the two modules do not depend on each other.
 *
 * <p>Nothing registered is the normal state of a new installation and is logged at debug level only; a registration that
 * cannot be used (missing or changed jar) is a warning that names paths and codes, never a secret.
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
        } catch (InfrastructureException e) {
            if (e.code() == InfrastructureCode.DRIVER_NOT_CONFIGURED) {
                LOG.debug("no JDBC driver is registered: the commands that need the database are refused", e);
            } else {
                LOG.warn("the registered JDBC driver is not usable: the commands that need the database are refused", e);
            }
            return false;
        } catch (BindException e) {
            LOG.warn("the JDBC driver registration cannot be read: the commands that need the database are refused", e);
            return false;
        }
    }
}
