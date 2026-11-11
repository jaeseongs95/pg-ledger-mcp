package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.McpTestSettings;
import org.pgledger.mcp.support.RawHttp;
import org.pgledger.mcp.support.RawHttp.Header;
import org.pgledger.mcp.support.RawHttp.Reply;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.WriteOutputs.Whoami;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.test.context.TestPropertySource;

/**
 * MCC cases about the transport and its guards: the handshake, the bearer, the Host and Origin headers, the health
 * endpoint, bad requests, the language of an error. The server of this class logs the security and the server code at
 * DEBUG (a context of its own), so that the check "the token is in no log line" looks at lines that exist.
 */
@McpServerTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"logging.level.org.springframework.security=DEBUG",
    "logging.level.org.pgledger.mcp=DEBUG"})
class TransportTest {

    private static final Header BEARER = Header.bearer(McpTestSettings.TOKEN);

    // ------------------------------------------------------------------------------------------------ MCC-014

    @Test
    @DisplayName("MCC-014: initialize over Streamable HTTP announces tools.listChanged and a session id")
    void initializeAnnouncesListChangedAndASession(RawHttp http) {
        Reply reply = http.initialize(BEARER);
        InitializeResult result = http.initializeResult(reply);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.header("Mcp-Session-Id")).isPresent().get().asString().isNotBlank();
        assertThat(result.capabilities().tools().listChanged()).isTrue();
    }

    @Test
    @DisplayName("MCC-014: DELETE closes the session, a later request with its id fails")
    void deleteClosesTheSession(RawHttp http) {
        String session = http.openSession(BEARER);
        Reply before = http.listTools(session, BEARER);

        Reply closed = http.delete("/mcp", BEARER, Header.session(session));
        Reply after = http.listTools(session, BEARER);

        assertThat(before.status()).isEqualTo(200);
        assertThat(closed.status()).isEqualTo(200);
        assertThat(after.status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------------ MCC-015

    /** An initialize request with the headers and the status it must get. */
    private record Bearer(String name, List<Header> headers, int status) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Bearer> bearers() {
        return Stream.of(
                new Bearer("no bearer", List.of(), 401),
                new Bearer("a wrong bearer", List.of(Header.bearer(McpTestSettings.TOKEN + "x")), 401),
                new Bearer("a wrong bearer of the same length",
                        List.of(Header.bearer("X" + McpTestSettings.TOKEN.substring(1))), 401),
                new Bearer("the right bearer", List.of(BEARER), 200));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bearers")
    @DisplayName("MCC-015: no bearer and a wrong one are 401, the right one is 200; the token is in no answer and no "
            + "log line")
    void theBearerDecides(Bearer bearer, RawHttp http, CapturedOutput output) {
        Reply reply = http.initialize(bearer.headers().toArray(new Header[0]));

        assertThat(reply.status()).isEqualTo(bearer.status());
        assertThat(reply.body()).doesNotContain(McpTestSettings.TOKEN);
        assertThat(output.getAll()).doesNotContain(McpTestSettings.TOKEN);
        assertThat(output.getAll()).doesNotContain(McpTestSettings.TOKEN.substring(1));
    }

    // ------------------------------------------------------------------------------------------------ MCC-016

    /** A Host header (a function of the server port) and the status of an initialize request that carries it. */
    private record HostHeader(String name, IntFunction<String> host, int status) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<HostHeader> hosts() {
        return Stream.of(
                new HostHeader("an allowed host", port -> McpTestSettings.ALLOWED_HOST, 200),
                new HostHeader("an allowed host with the port", port -> McpTestSettings.ALLOWED_HOST + ":" + port,
                        200),
                new HostHeader("an allowed host in mixed case",
                        port -> McpTestSettings.ALLOWED_HOST.toUpperCase(Locale.ROOT), 200),
                new HostHeader("a host that is not listed", port -> "rebinding.example", 403),
                new HostHeader("a host that is not listed with the port", port -> "rebinding.example:" + port, 403));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("MCC-016: the Host header must be on the allow list (any case, with or without a port), otherwise 403")
    void theHostHeaderIsChecked(HostHeader host, RawHttp http, ApplicationContext context) {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);

        Reply reply = http.initialize(BEARER, Header.host(host.host().apply(port)));

        assertThat(reply.status()).isEqualTo(host.status());
    }

    // ------------------------------------------------------------------------------------------------ MCC-018

    /** The body of the health endpoint: the status and the names of the health groups, nothing else. */
    private record Health(List<String> groups, String status) {
    }

    @Test
    @DisplayName("MCC-018: GET /healthz without a bearer is 200 with the status and the group names only")
    void healthzIsOpenAndTerse(RawHttp http, McpSession session) {
        Reply reply = http.get("/healthz");

        Health health = session.mapper().readValue(reply.body(), Health.class);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(health).isEqualTo(new Health(List.of("liveness", "readiness"), "UP"));
        // a body with any other key (components, details) would not survive the round trip through the record
        assertThat(session.mapper().writeValueAsString(health)).isEqualTo(reply.body());
    }

    static Stream<String> otherActuatorPaths() {
        return Stream.of("/actuator", "/actuator/health", "/health", "/env", "/beans", "/metrics", "/info",
                "/loggers", "/mappings", "/configprops", "/heapdump", "/threaddump");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherActuatorPaths")
    @DisplayName("MCC-018: no other Actuator endpoint is exposed (404 even with the bearer)")
    void otherActuatorEndpointsAreNotThere(String path, RawHttp http) {
        assertThat(http.get(path, BEARER).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------------ MCC-019

    @Test
    @DisplayName("MCC-019: an unknown tool is a JSON-RPC error, writes nothing, and the session keeps serving")
    void anUnknownToolIsAProtocolError(McpSession session, Scenario scenario) {
        assertThatThrownBy(() -> session.call("no_such_tool", new Calls.Nothing()))
                .isInstanceOfSatisfying(McpError.class, error -> {
                    assertThat(error.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
                    assertThat(String.valueOf(error.getJsonRpcError().data())).contains("no_such_tool");
                });

        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(session.ok("ledger_whoami", Calls.Whoami.as(Scenario.AUTHOR), Whoami.class).author())
                .isEqualTo(Scenario.AUTHOR);
    }

    /** A request body that is not a valid JSON-RPC message and what the server must make of it. */
    private record BadFrame(String name, Function<McpSession, String> body) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<BadFrame> badFrames() {
        return Stream.of(
                new BadFrame("a JSON text cut in the middle",
                        session -> session.mapper().writeValueAsString(new Calls.Whoami(Scenario.AUTHOR, null))
                                .substring(0, 12)),
                new BadFrame("valid JSON that is no JSON-RPC message",
                        session -> session.mapper().writeValueAsString(new Calls.Nothing())),
                new BadFrame("a text that is not JSON at all", session -> "this is not JSON"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFrames")
    @DisplayName("MCC-019: a malformed frame is a client error (never a 500), writes nothing, the server keeps serving")
    void aMalformedFrameIsAClientError(BadFrame frame, RawHttp http, McpSession session, Scenario scenario) {
        String sessionId = http.openSession(BEARER);

        Reply reply = http.postText("/mcp", frame.body().apply(session), BEARER, Header.session(sessionId));
        Reply after = http.callTool(sessionId, "ledger_whoami", Calls.Whoami.as(Scenario.AUTHOR), BEARER);

        assertThat(reply.status()).isBetween(400, 499);
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
        assertThat(after.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("MCC-019: a request for an unknown method is a JSON-RPC error answer")
    void anUnknownMethodIsAJsonRpcError(RawHttp http) {
        String sessionId = http.openSession(BEARER);

        Reply reply = http.post("/mcp", new JSONRPCRequest("2.0", "no/such/method", 9, null), BEARER,
                Header.session(sessionId));

        assertThat(reply.status()).isLessThan(500);
        JSONRPCResponse response = http.response(reply);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(-32601);
    }

    // ------------------------------------------------------------------------------------------------ MCC-021

    static Stream<String> languages() {
        return Stream.of("ko", "en", "fr-CA", "ko-KR,en;q=0.5");
    }

    @ParameterizedTest(name = "Accept-Language {0}")
    @MethodSource("languages")
    @DisplayName("MCC-021 (superseded: model-facing text is English only): an error call returns the English message "
            + "and the same token whatever Accept-Language says")
    void anErrorIsEnglishWhateverTheLanguage(String language, RawHttp http,
            @Autowired MessageSource messages) {
        String sessionId = http.openSession(BEARER);
        String english = messages.getMessage("error.HOST_ATTESTATION_REQUIRED", null, Locale.ENGLISH);
        String korean = messages.getMessage("error.HOST_ATTESTATION_REQUIRED", null, Locale.KOREAN);

        Reply reply = http.callTool(sessionId, "ledger_whoami", Calls.Whoami.withoutAttestation(), BEARER,
                Header.language(language));
        CallToolResult result = http.toolResult(reply);

        assertThat(korean).as("the Korean bundle must differ, or this case proves nothing").isNotEqualTo(english);
        assertThat(result.isError()).isTrue();
        assertThat(McpSession.text(result)).isEqualTo("HOST_ATTESTATION_REQUIRED: " + english);
        assertThat(http.convert(result.structuredContent(), ToolError.class).code())
                .isEqualTo("HOST_ATTESTATION_REQUIRED");    }

    // ------------------------------------------------------------------------------------------------ MCC-022

    /** An Origin header (or none) and the status of an initialize request that carries it. */
    private record OriginHeader(String name, List<Header> headers, int status) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<OriginHeader> origins() {
        return Stream.of(
                new OriginHeader("no Origin (not a browser)", List.of(), 200),
                new OriginHeader("an allowed Origin", List.of(Header.origin(McpTestSettings.ALLOWED_ORIGIN)), 200),
                new OriginHeader("an allowed Origin in capitals",
                        List.of(Header.origin(McpTestSettings.ALLOWED_ORIGIN.toUpperCase(Locale.ROOT))), 200),
                new OriginHeader("an Origin that is not listed", List.of(Header.origin("http://evil.example")), 403),
                new OriginHeader("the Origin null of a sandboxed page", List.of(Header.origin("null")), 403));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("origins")
    @DisplayName("MCC-022: a browser request from an Origin that is not allowed is refused with 403")
    void theOriginIsChecked(OriginHeader origin, RawHttp http) {
        Header[] headers = Stream.concat(Stream.of(BEARER), origin.headers().stream()).toArray(Header[]::new);

        Reply reply = http.initialize(headers);

        assertThat(reply.status()).isEqualTo(origin.status());
    }
}
