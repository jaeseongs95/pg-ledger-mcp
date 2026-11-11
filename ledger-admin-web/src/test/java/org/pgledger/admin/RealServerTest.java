package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.RealHttp;
import org.pgledger.admin.support.RealHttp.Reply;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The cases that need a real servlet container, because they are about what Tomcat and the firewall do with HTTP
 * itself: the attributes of the session cookie and the session id after the sign-in (ADM-009, ADM-014, fixation), the
 * end of a session (ADM-012), the Host allow list (ADM-027) and the paths that must never reach a handler (ADM-035).
 */
@SpringBootTest(classes = LedgerAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {AdminTestSettings.TOKEN_PROPERTY, AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.INSECURE_COOKIE,
            AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
            AdminTestSettings.NO_JMX})
@ActiveProfiles("embedded")
@Import({AdminTestInfrastructure.class, RealServerTest.Sessions.class})
class RealServerTest {

    /** Keeps the sessions the container creates, so that a test can shorten the life of one. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Sessions {

        static final class Catcher implements HttpSessionListener, HttpSessionIdListener {

            private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();

            @Override
            public void sessionCreated(HttpSessionEvent event) {
                sessions.put(event.getSession().getId(), event.getSession());
            }

            @Override
            public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
                sessions.remove(oldSessionId);
                sessions.put(event.getSession().getId(), event.getSession());
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                sessions.remove(event.getSession().getId());
            }

            HttpSession session(String id) {
                return sessions.get(id);
            }
        }

        @Bean
        Catcher sessionCatcher() {
            return new Catcher();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Sessions.Catcher catcher;

    private RealHttp http() {
        return new RealHttp(port);
    }

    @Test
    @DisplayName("ADM-009/ADM-014: the session cookie is HttpOnly and SameSite=Strict under the configured name; the sign-in "
            + "gives a new session id and the old one is dead (fixation)")
    void adm009_cookieAndFixation() {
        RealHttp http = http();
        Reply page = http.get("/login");

        String cookie = page.sessionCookieLine().orElseThrow();
        assertThat(cookie).startsWith("LEDGER_ADMIN_SESSION=").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/").doesNotContain("Secure");

        String before = page.sessionId().orElseThrow();
        Reply login = http.postForm("/login", "username=owner&token=" + AdminTestSettings.TOKEN + "&_csrf=" + page.csrf(),
                "Cookie", RealHttp.cookie(before));
        String after = login.sessionId().orElseThrow();

        assertThat(login.status()).isEqualTo(302);
        assertThat(after).isNotEqualTo(before);
        assertThat(login.sessionCookieLine().orElseThrow()).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(http.get("/api/session", "Cookie", RealHttp.cookie(after), "Accept", "application/json").status())
                .isEqualTo(200);
        assertThat(http.get("/api/session", "Cookie", RealHttp.cookie(before), "Accept", "application/json").status())
                .as("the session id from before the sign-in is not a session any more").isEqualTo(401);
    }

    @Test
    @DisplayName("ADM-012: a session lives 12 hours by configuration and is rejected once its lifetime has passed")
    void adm012_sessionLifetime() throws InterruptedException {
        RealHttp http = http();
        String session = http.signIn(AdminTestSettings.TOKEN);
        HttpSession held = catcher.session(session);

        assertThat(held.getMaxInactiveInterval()).isEqualTo(12 * 60 * 60);
        assertThat(http.get("/api/session", "Cookie", RealHttp.cookie(session), "Accept", "application/json").status())
                .isEqualTo(200);

        held.setMaxInactiveInterval(1);
        Thread.sleep(2_200);

        assertThat(http.get("/api/session", "Cookie", RealHttp.cookie(session), "Accept", "application/json").status())
                .isEqualTo(401);
    }

    private record HostHeader(String name, String host, int status) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<HostHeader> hosts() {
        return Stream.of(
                new HostHeader("a listed host", "localhost", 200),
                new HostHeader("a listed host with a port", "localhost:8865", 200),
                new HostHeader("a listed host in upper case", "LOCALHOST", 200),
                new HostHeader("an unlisted host", "rebinding.example", 403),
                new HostHeader("an unlisted host with a port", "rebinding.example:8865", 403));
    }

    @ParameterizedTest
    @MethodSource("hosts")
    @DisplayName("ADM-027: the Host header must be on the allow list; otherwise 403 DNS_REBINDING_BLOCKED, also on /healthz")
    void adm027_hostAllowList(HostHeader host) {
        RealHttp http = http();

        Reply health = http.get("/healthz", "Host", host.host(), "Accept", "application/json");
        Reply login = http.get("/login", "Host", host.host(), "Accept", "application/json");

        assertThat(health.status()).isEqualTo(host.status());
        assertThat(login.status()).isEqualTo(host.status());
        if (host.status() == 403) {
            assertThat(health.body()).contains("\"DNS_REBINDING_BLOCKED\"");
        }
    }

    @Test
    @DisplayName("ADM-027: an empty Host header is refused")
    void adm027_emptyHost() throws IOException {
        String answer = rawRequest("GET /healthz HTTP/1.1\r\nHost: \r\nAccept: application/json\r\nConnection: close\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 403");
    }

    static Stream<String> hostilePaths() {
        return Stream.of("/..%2f..%2fetc/x", "//etc/x", "/.%2e/x", "/css/..%2f..%2fapplication.yml", "/css/%2e%2e/x",
                "/css/admin.css%00");
    }

    @ParameterizedTest
    @MethodSource("hostilePaths")
    @DisplayName("ADM-035: a path with an encoded slash, a double slash or a dot segment is refused (400) and never serves a file")
    void adm035_hostilePaths(String path) throws IOException {
        String answer = rawRequest("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\n"
                + "Connection: close\r\n\r\n");

        assertThat(answer.lines().findFirst().orElseThrow()).matches("HTTP/1\\.1 (400|404).*");
        assertThat(answer).doesNotContain("spring:").doesNotContain("root:");
    }

    /** A request exactly as written (the JDK client normalises what is sent), and the whole answer. */
    private String rawRequest(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).lines()
                    .collect(Collectors.joining("\n"));
        }
    }
}
