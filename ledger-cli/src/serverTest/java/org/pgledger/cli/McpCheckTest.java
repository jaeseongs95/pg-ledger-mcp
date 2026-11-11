package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.McpTestServer;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.domain.rule.ToolContract;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-010, CLI-011 and CLI-040: {@code mcp-check} against a real MCP server started in the test (the MCP Java SDK server over
 * Streamable HTTP in an embedded Tomcat, see {@link McpTestServer}), with the application context of the CLI and no database:
 * the command needs none. Which tools the expected list holds is the business of {@link ToolContract}, which the catalogue
 * tests of the MCP server module compare with the tools of the product server.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class McpCheckTest {

    private static final String TOKEN = "Bearer-Token-0123456789-unique-value";
    private static final String WRONG_TOKEN = "Wrong-Token-9876543210-unique-value";

    @TempDir
    Path tmp;

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.run(tmp.resolve("secrets"), null, output, args);
    }

    private McpTestServer server(List<String> tools) {
        try {
            return McpTestServer.start(Files.createDirectories(tmp.resolve("tomcat")), TOKEN, tools);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertNoSecret(CapturedOutput captured, RecordingOutput output) {
        assertThat(captured.getAll()).doesNotContain(TOKEN).doesNotContain(WRONG_TOKEN);
        assertThat(output.info()).doesNotContain(TOKEN).doesNotContain(WRONG_TOKEN);
    }

    @Test
    @DisplayName("CLI-010 mcp-check connects with the MCP Java SDK client and the bearer token, lists the tools and verifies every expected name: exit 0, the output prints the count")
    void checksARunningServer(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();
        try (McpTestServer server = server(ToolContract.all())) {
            Outcome result = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), output, "mcp-check", "--url=" + server.url());

            assertThat(result.exitCode()).isZero();
            assertThat(result.failure()).isNull();
            assertThat(output.info()).contains("22 tools listed").contains("all 22 expected tools").contains(server.url());
        }
        assertNoSecret(captured, output);
    }

    @Test
    @DisplayName("CLI-010 the list the command expects is the 22 names of the contract: ten write tools and twelve read tools")
    void theExpectedListIsTheContract() {
        assertThat(ToolContract.all()).hasSize(22).doesNotHaveDuplicates();
        assertThat(ToolContract.WRITE).hasSize(10);
        assertThat(ToolContract.READ).hasSize(12);
    }

    @Test
    @DisplayName("CLI-010 a server that lists more tools than expected is accepted; the count is the number it lists")
    void extraToolsAreAccepted(Cli cli) {
        List<String> tools = new ArrayList<>(ToolContract.all());
        tools.add("ledger_future_tool");
        RecordingOutput output = new RecordingOutput();
        try (McpTestServer server = server(tools)) {
            Outcome result = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), output, "mcp-check", "--url=" + server.url());

            assertThat(result.exitCode()).isZero();
            assertThat(output.info()).contains("23 tools listed").contains("all 22 expected tools");
        }
    }

    @Test
    @DisplayName("CLI-040 the bearer token comes from the secret store (the setting ledger.mcp.token, read as a configtree file) when no option gives it; the default URL is the local server")
    void tokenFromTheSecretStore(Cli cli, CapturedOutput captured) throws IOException {
        Path secrets = Files.createDirectories(tmp.resolve("secrets"));
        Files.writeString(secrets.resolve("ledger.mcp.token"), TOKEN);
        RecordingOutput output = new RecordingOutput();
        try (McpTestServer server = server(ToolContract.all())) {
            Outcome result = run(cli, output, "mcp-check", "--url=" + server.url());

            assertThat(result.exitCode()).isZero();
            assertThat(output.info()).contains("22 tools listed");
        }
        assertNoSecret(captured, output);
    }

    @Test
    @DisplayName("CLI-040 without any token the command is MCP_TOKEN_MISSING (exit 1) and says how to store it; nothing is sent")
    void missingToken(Cli cli, CapturedOutput captured) {
        try (McpTestServer server = server(ToolContract.all())) {
            Outcome result = run(cli, new RecordingOutput(), "mcp-check", "--url=" + server.url());

            assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.MCP_TOKEN_MISSING);
            assertThat(captured.getAll()).contains("secret-set ledger.mcp.token");
        }
    }

    @Test
    @DisplayName("CLI-011 a tool of the contract is missing on the server: MCP_TOOL_MISSING (exit 1) naming it, without the token")
    void missingTool(Cli cli, CapturedOutput captured) {
        List<String> tools = new ArrayList<>(ToolContract.all());
        tools.remove("ledger_stats");
        RecordingOutput output = new RecordingOutput();
        try (McpTestServer server = server(tools)) {
            Outcome result = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), output, "mcp-check", "--url=" + server.url());

            assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.MCP_TOOL_MISSING);
            assertThat(result.refusal().orElseThrow().arguments().get(1)).isEqualTo("ledger_stats");
            assertThat(captured.getAll()).contains("ledger_stats");
        }
        assertNoSecret(captured, output);
    }

    @Test
    @DisplayName("CLI-011 a wrong bearer token is refused by the server: MCP_AUTHORIZATION_REFUSED (exit 1), and neither the wrong nor the right token is printed")
    void wrongBearer(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();
        try (McpTestServer server = server(ToolContract.all())) {
            Outcome result = run(cli.withArguments("--ledger.mcp.token=" + WRONG_TOKEN), output, "mcp-check",
                    "--url=" + server.url());

            assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.MCP_AUTHORIZATION_REFUSED);
            assertThat(captured.getAll()).contains("the MCP check of").contains("exception chain").contains("Exception");
        }
        assertNoSecret(captured, output);
    }

    @Test
    @DisplayName("CLI-011 an URL where nothing listens: MCP_UNREACHABLE (exit 1) naming the URL, without the token")
    void unreachable(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();
        String url;
        try (McpTestServer server = server(ToolContract.all())) {
            url = server.url();
        }

        Outcome result = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), output, "mcp-check", "--url=" + url);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.MCP_UNREACHABLE);
        assertThat(captured.getAll()).contains("exception chain").contains("Exception");
        assertThat(captured.getAll()).contains(url);
        assertNoSecret(captured, output);
    }

    @Test
    @DisplayName("CLI-040 a URL that is not http or https is a usage error (exit 2) before any connection is made, and the token is not printed")
    void aBadUrlNeverReachesTheNetwork(Cli cli, CapturedOutput captured) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli.withArguments("--ledger.mcp.token=" + TOKEN), output, "mcp-check",
                "--url=file:///etc/passwd");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(ErrorCode.INVALID_OPTION_VALUE);
        assertNoSecret(captured, output);
    }
}
