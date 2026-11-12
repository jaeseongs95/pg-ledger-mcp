package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.pgledger.admin.support.RealHttp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;

/**
 * ADM-056 (security audit): with the default settings the session cookie is {@code Secure}. This context does not carry
 * the property that the other real-server tests use to talk plain HTTP.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX})
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class SecureCookieDefaultTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("ADM-056: by default the session cookie is Secure, HttpOnly and SameSite=Strict, also on a plain HTTP request")
    void adm056_theCookieIsSecureByDefault() {
        String cookie = new RealHttp(port).get("/login").sessionCookieLine().orElseThrow();

        assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=Strict");
    }
}
