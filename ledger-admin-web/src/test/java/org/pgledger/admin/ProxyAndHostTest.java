package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.pgledger.admin.support.RealHttp;
import org.pgledger.admin.support.RealHttp.Reply;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADM-049 and ADM-051 (security audit D1, D2): what Tomcat believes of the forwarding headers, against a real server.
 * The proxy of the host is {@code 127.0.0.1}; a client that connects from another address, even another loopback
 * address, is not the proxy and chooses neither the address the lock counts nor the host the firewall checks.
 */
@ContextConfiguration(initializers = TestSchemaInitializer.class)
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY,
            AdminTestSettings.INSECURE_COOKIE, AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER,
            AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX})
@ActiveProfiles("embedded")
@Import(AdminTestInfrastructure.class)
class ProxyAndHostTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    private Reply bearer(RealHttp http, String token, String forwardedFor) {
        return http.get("/api/session", "Accept", "application/json", "Authorization", "Bearer " + token,
                "X-Forwarded-For", forwardedFor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0:0:0:0:0:0:0:1", "::1"})
    @DisplayName("ADM-049: the trusted proxies are the loopback address of the host")
    void adm049_loopbackIsTrusted(String proxy) {
        assertThat(proxies().matcher(proxy).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.2", "127.1.2.3", "10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255",
        "100.64.0.1", "169.254.1.1", "203.0.113.9", "2001:db8::1", "::2"})
    @DisplayName("ADM-049: no other address, also no private network and no other loopback address, is a trusted proxy")
    void adm049_nothingElseIsTrusted(String peer) {
        assertThat(proxies().matcher(peer).matches()).as(peer).isFalse();
    }

    private Pattern proxies() {
        return Pattern.compile(environment.getRequiredProperty("server.tomcat.remoteip.internal-proxies"));
    }

    @Test
    @DisplayName("ADM-049: a peer that is not the proxy cannot choose the address the lock counts with X-Forwarded-For")
    void adm049_anUntrustedPeerCannotRotateItsAddress() {
        RealHttp attacker = new RealHttp(port, InetAddress.ofLiteral("127.0.0.2"));

        List<Integer> statuses = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            statuses.add(bearer(attacker, WRONG, "203.0.113." + i).status());
        }

        assertThat(statuses).containsExactly(401, 401, 401, 401, 401, 429, 429);
    }

    @Test
    @DisplayName("ADM-049: the proxy of the host does tell the client address: the forwarded address is what is locked")
    void adm049_theProxyTellsTheClientAddress() {
        RealHttp proxy = new RealHttp(port);

        List<Integer> statuses = Stream.generate(() -> bearer(proxy, WRONG, "198.51.100.77").status()).limit(6).toList();
        Reply otherClient = bearer(proxy, AdminTestSettings.TOKEN, "198.51.100.78");

        assertThat(statuses).containsExactly(401, 401, 401, 401, 401, 429);
        assertThat(otherClient.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADM-051: a forwarded host does not change the host the firewall checks: an unlisted Host with a listed "
            + "X-Forwarded-Host is refused, a listed Host with an unlisted X-Forwarded-Host is served")
    void adm051_theForwardedHostIsNotBelieved() {
        RealHttp http = new RealHttp(port);

        Reply forged = http.get("/healthz", "Host", "rebinding.example", "X-Forwarded-Host", "localhost", "Accept",
                "application/json");
        Reply ignored = http.get("/healthz", "Host", "localhost", "X-Forwarded-Host", "rebinding.example", "Accept",
                "application/json");

        assertThat(forged.status()).isEqualTo(403);
        assertThat(forged.body()).contains("\"DNS_REBINDING_BLOCKED\"");
        assertThat(ignored.status()).isEqualTo(200);
    }
}
