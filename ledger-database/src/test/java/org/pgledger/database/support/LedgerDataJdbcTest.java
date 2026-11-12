package org.pgledger.database.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ContextConfiguration;

/**
 * A repository test of the database module as a {@code @DataJdbcTest} slice (principle 7.2) on the PostgreSQL of the test PC: the
 * repositories of the ledger ({@link LedgerRepositorySlice}) in a schema of its own that the real schema files have built, every
 * test in a transaction that Spring rolls back. Boot's own replacement of the database is switched off: the DataSource is the
 * one of the test database support. A test that needs a row that another connection can see (a committed one), the pool of
 * another account or time zone, or the DDL itself keeps its own form (the reasons are in the report of unit W7).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = LedgerRepositorySlice.class, initializers = TestSchemaInitializer.class)
public @interface LedgerDataJdbcTest {
}
