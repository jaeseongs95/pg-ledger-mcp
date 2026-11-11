package org.pgledger.application.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A service test class that runs once for H2 and once for PostgreSQL (catalogue rows marked DBMS {@code yes}). It
 * implements {@link DbmsAware} (the parameter field is {@code dbms}) and takes a {@link Scenario} as a method
 * parameter.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedClass
@EnumSource(Dbms.class)
@ExtendWith(LedgerAppExtension.class)
public @interface ServiceTest {
}
