package org.pgledger.application.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.apptest.LedgerTestCategories;
import org.pgledger.apptest.LedgerTestInfrastructure;
import org.pgledger.apptest.LedgerTestSettings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A service test class: the ledger services in the Spring Boot application of {@link TestApplication}, on an
 * embedded H2 database that the real schema files have built, with the categories of an installation that has been
 * set up. Spring's test context cache shares the one context between all test classes; every test works in a
 * project of its own (a {@link Scenario}, a method parameter), so they do not collide.
 *
 * <p>No transaction wraps a test method: the class is not {@code @Transactional}, so Spring's transactional test
 * listener starts none and nothing is rolled back at the end of a test. The services open and commit their own
 * transactions, and the race tests (SVC-RACE, SVC-RCP-09..11, SVC-GATE-18, SVC-GRT-06, SVC-BEF-08/09) run real
 * threads that commit. {@link ScenarioExtension} fails a test that would run inside a transaction.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {LedgerTestSettings.URL, LedgerTestSettings.POOL, LedgerTestSettings.UTC_SESSION,
            LedgerTestSettings.QUIET_BANNER, LedgerTestSettings.QUIET_LOG, LedgerTestSettings.NO_JMX})
@Import({LedgerTestInfrastructure.class, LedgerTestCategories.class})
@ExtendWith(ScenarioExtension.class)
public @interface LedgerServiceTest {
}
