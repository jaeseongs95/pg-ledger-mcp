package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.RealHttp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADM-026 (trusted proxy): behind a reverse proxy that Tomcat trusts (here the loopback address, one of the internal
 * proxies of Tomcat by default), {@code X-Forwarded-Proto: https} makes the session cookie {@code Secure}.
 */
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX})
@ActiveProfiles("embedded")
@Import(AdminTestInfrastructure.class)
class ForwardedProtoTrustedTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("ADM-026: X-Forwarded-Proto: https from a trusted proxy makes the session cookie Secure; without it, no")
    void adm026_trustedProxyMakesTheCookieSecure() {
        RealHttp http = new RealHttp(port);

        String forwarded = http.get("/login", "X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.9")
                .sessionCookieLine().orElseThrow();
        String plain = http.get("/login").sessionCookieLine().orElseThrow();

        assertThat(forwarded).contains("Secure").contains("SameSite=Strict").contains("HttpOnly");
        assertThat(plain).doesNotContain("Secure");
    }
}
