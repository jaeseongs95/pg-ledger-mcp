package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.console.McpHealthClient;
import org.pgledger.admin.console.McpStatus;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.StandInMcpServer;
import org.pgledger.admin.support.StandInMcpServer.Mode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * ADM-045, ADM-046: the MCP status of the overview is one Spring RestClient GET of exactly the configured URL, with
 * time limits and nothing secret; whatever happens the page renders, and only a configured http(s) URL is ever asked.
 */
@AdminTest
class McpHealthTest {

    private static final StandInMcpServer MCP = StandInMcpServer.start();

    @DynamicPropertySource
    static void health(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.mcp.health-url", MCP::healthUrl);
        registry.add("ledger.admin.mcp.timeout", () -> "700ms");
    }

    @AfterAll
    static void stop() {
        MCP.close();
    }

    @Autowired
    private MockMvc mvc;

    private static LedgerAdminProperties properties(String url, Duration timeout) {
        return new LedgerAdminProperties("a-token-of-sixteen-chars", null, List.of("localhost"),
                org.springframework.util.unit.DataSize.ofKilobytes(64), Path.of("/unused"),
                new LedgerAdminProperties.Lockout(5, Duration.ofSeconds(60), Duration.ofSeconds(60)),
                new LedgerAdminProperties.Oidc(List.of()), new LedgerAdminProperties.Mcp(url, timeout));
    }

    private record Expected(Mode mode, McpStatus.State state, McpStatus.Reason reason, String text) {

        @Override
        public String toString() {
            return mode.name();
        }
    }

    static List<Expected> answers() {
        return List.of(
                new Expected(Mode.UP, McpStatus.State.UP, McpStatus.Reason.NONE, "Up"),
                new Expected(Mode.SERVICE_UNAVAILABLE, McpStatus.State.DOWN, McpStatus.Reason.HTTP_ERROR,
                        "answered with an error status"),
                new Expected(Mode.REPORTS_DOWN, McpStatus.State.DOWN, McpStatus.Reason.REPORTS_DOWN,
                        "reports that it is down"),
                new Expected(Mode.NOT_A_REPORT, McpStatus.State.DOWN, McpStatus.Reason.INVALID_ANSWER,
                        "not a health report"),
                new Expected(Mode.HANGS, McpStatus.State.DOWN, McpStatus.Reason.TIMEOUT, "did not answer in time"));
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class)
    @DisplayName("ADM-045: the overview shows UP / DOWN with the reason for every answer of the MCP server; the page renders")
    void adm045_overviewShowsTheStatus(Mode mode) throws Exception {
        Expected expected = answers().stream().filter(e -> e.mode() == mode).findFirst().orElseThrow();
        MCP.answer(mode);
        Browser owner = Browser.signedIn(mvc);

        String page = owner.perform(owner.get("/")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        owner.perform(owner.get("/api/overview").accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.mcp.state").value(expected.state().name()))
                .andExpect(jsonPath("$.mcp.reason").value(expected.reason().name()));

        assertThat(page).contains("status-" + expected.state().name()).contains(expected.text());
    }

    @Test
    @DisplayName("ADM-045: the request is a GET of exactly the configured URL and carries no credential")
    void adm045_exactRequestWithoutSecrets() throws Exception {
        MCP.answer(Mode.UP);
        int before = MCP.seen().size();
        Browser owner = Browser.signedIn(mvc);

        owner.perform(owner.get("/"));

        StandInMcpServer.Seen request = MCP.seen().get(before);
        assertThat(request.target()).isEqualTo("/healthz?probe=1");
        assertThat(request.headerNames()).doesNotContain("authorization", "cookie", "x-api-key");
    }

    @Test
    @DisplayName("ADM-045: a refused connection is DOWN (unreachable)")
    void adm045_refusedConnection() throws Exception {
        String closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = Integer.toString(socket.getLocalPort());
        }
        McpHealthClient client = new McpHealthClient(properties("http://localhost:" + closedPort + "/healthz",
                Duration.ofSeconds(1)), RestClient.builder());

        assertThat(client.status()).isEqualTo(new McpStatus(McpStatus.State.DOWN, McpStatus.Reason.UNREACHABLE));
    }

    @Test
    @DisplayName("ADM-046: without a configured URL the indicator says not configured and no request is made")
    void adm046_notConfigured() {
        McpHealthClient client = new McpHealthClient(properties(null, Duration.ofSeconds(1)), RestClient.builder());
        int before = MCP.seen().size();

        assertThat(client.status()).isEqualTo(new McpStatus(McpStatus.State.NOT_CONFIGURED, McpStatus.Reason.NONE));
        assertThat(MCP.seen()).hasSize(before);
    }

    @Test
    @DisplayName("ADM-046: a URL that is not http or https stops the start; a request parameter never chooses the URL")
    void adm046_onlyHttpUrlsFromConfiguration() throws Exception {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesOnly.class);

        runner.withPropertyValues("ledger.admin.mcp.health-url=ftp://example.test/healthz")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ledger.admin.mcp.health-url=file:///etc/passwd")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("ledger.admin.mcp.health-url=https://example.test/healthz")
                .run(context -> assertThat(context).hasNotFailed());

        int before = MCP.seen().size();
        Browser owner = Browser.signedIn(mvc);
        owner.perform(owner.get("/?health-url=http://localhost:1/other&url=http://localhost:1/other"))
                .andExpect(status().isOk());
        assertThat(MCP.seen().subList(before, MCP.seen().size())).allMatch(request -> "/healthz?probe=1"
                .equals(request.target()));
    }

    @EnableConfigurationProperties(LedgerAdminProperties.class)
    static class PropertiesOnly {
    }
}
