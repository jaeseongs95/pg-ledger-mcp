package org.pgledger.database.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A test class that runs once for H2 and once for PostgreSQL. It implements {@link DbmsAware} (the parameter field is
 * {@code dbms}) and takes the migrated database as a {@link LedgerTestContext} parameter of its methods.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedClass
@EnumSource(Dbms.class)
@ExtendWith(LedgerDatabaseExtension.class)
public @interface DatabaseTest {
}
