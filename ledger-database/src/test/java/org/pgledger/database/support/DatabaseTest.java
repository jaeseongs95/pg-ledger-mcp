package org.pgledger.database.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A test class that takes the migrated test database (a schema of its own in the PostgreSQL of the test PC) as a {@link LedgerTestContext} parameter of its methods (and
 * {@link Databases} for databases of its own); see {@link LedgerDatabaseExtension}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(LedgerDatabaseExtension.class)
public @interface DatabaseTest {
}
