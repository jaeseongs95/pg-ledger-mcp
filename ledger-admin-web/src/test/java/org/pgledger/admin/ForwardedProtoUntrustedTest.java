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
import org.springframework.test.context.ActiveProfiles;

/**
 * ADM-026 (untrusted peer): when the peer is not one of the configured trusted proxies (the only trusted proxy here is
 * another address), the forwarding headers are ignored and the cookie is not made {@code Secure} by them.
 */
@ContextConfiguration(initializers = TestSchemaInitializer.class)
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.INSECURE_COOKIE,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX, "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1"})
@ActiveProfiles("embedded")
@Import(AdminTestInfrastructure.class)
class ForwardedProtoUntrustedTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("ADM-026: X-Forwarded-Proto: https from a peer that is not a trusted proxy is ignored")
    void adm026_untrustedPeerCannotMakeTheCookieSecure() {
        RealHttp http = new RealHttp(port);

        String cookie = http.get("/login", "X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.9")
                .sessionCookieLine().orElseThrow();

        assertThat(cookie).doesNotContain("Secure").contains("SameSite=Strict");
    }
}
