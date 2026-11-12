package org.pgledger.database.driver;

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
 * True when an application can have its database: a registered JDBC driver that the product's own loader accepts (jar
 * present, digest as registered, class loadable). Otherwise none of the database beans exists: the admin web starts in setup mode (login and driver
 * registration only), and the command line tool refuses a command that needs the database with DRIVER_NOT_CONFIGURED
 * while {@code secret-set} and {@code driver-register} still run on a machine that has no JDBC driver. The admin web and
 * the command line tool use this one condition; the command line tool adds only the question which commands use the
 * database ({@link #needsDatabase}).
 *
 * <p>Why a condition of ours (principle 2.1): the registration is read from the environment, which Spring Boot has already
 * filled from {@code spring.config.import} when conditions are evaluated; Boot's own conditions
 * ({@code @ConditionalOnProperty}, {@code @ConditionalOnBean}) cannot ask whether the registered jar still matches its
 * digest. {@link Condition} is the Spring Framework contract for exactly this decision
 * (https://docs.spring.io/spring-framework/reference/core/beans/java/composing-configuration-classes.html). A condition is
 * made by Spring with {@code new} before any bean exists, so the {@link DriverLoader} is made here with {@code new} as
 * well, and not injected.
 *
 * <p>Nothing registered is the normal state of a new installation and is logged at debug level only; a registration that
 * cannot be used (missing or changed jar) is a warning that names paths and codes, never a secret.
 */
public class DriverRegisteredCondition implements Condition {

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegisteredCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (!needsDatabase(context)) {
            LOG.debug("the command does not use the ledger database: no database bean is made, whatever is registered");
            return false;
        }
        try {
            DriverRegistration registration = Binder.get(context.getEnvironment())
                    .bind("ledger.jdbc.driver", DriverRegistration.class)
                    .orElseGet(() -> new DriverRegistration(null, null, null, null));
            new DriverLoader().load(registration).close();
            return true;
        } catch (InfrastructureException e) {
            if (e.code() == InfrastructureCode.DRIVER_NOT_CONFIGURED) {
                LOG.debug("no JDBC driver is registered: nothing that needs the database is made", e);
            } else {
                LOG.warn("the registered JDBC driver is not usable: nothing that needs the database is made", e);
            }
            return false;
        } catch (BindException e) {
            LOG.warn("the JDBC driver registration cannot be read: nothing that needs the database is made", e);
            return false;
        }
    }

    /**
     * Whether the running program uses the database at all; true unless a program knows better (the command line tool
     * answers by the command it was started with).
     */
    protected boolean needsDatabase(ConditionContext context) {
        return true;
    }
}
