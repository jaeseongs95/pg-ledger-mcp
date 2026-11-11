package org.pgledger.mcp.support;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain HTTP to the running server (the JDK {@code HttpClient}) for the cases that are about HTTP itself: status
 * codes, headers, a session that is closed, a body that is not a valid JSON-RPC message. The JSON-RPC messages are
 * the types of the MCP SDK, written with the mapper of the SDK; nothing is a hand-built JSON text.
 */
public final class RawHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String ACCEPTED_TYPES = "application/json, text/event-stream";

    /** One request header. */
    public record Header(String name, String value) {

        public static Header bearer(String token) {
            return new Header("Authorization", "Bearer " + token);
        }

        public static Header host(String value) {
            return new Header("Host", value);
        }

        public static Header origin(String value) {
            return new Header("Origin", value);
        }

        public static Header language(String value) {
            return new Header("Accept-Language", value);
        }

        public static Header session(String id) {
            return new Header(HttpHeaders.MCP_SESSION_ID, id);
        }
    }

    /** What the server answered. */
    public record Reply(int status, java.net.http.HttpHeaders headers, String body) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }

        /**
         * The JSON text of the answer: the body itself, or the {@code data} lines of an event stream (the server
         * answers a request with one event).
         */
        public String payload() {
            boolean stream = header("Content-Type").filter(type -> type.startsWith("text/event-stream")).isPresent();
            return stream ? body.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).strip()).collect(Collectors.joining("\n")) : body;
        }
    }

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT).build();
    private final String base;
    private final JsonMapper mapper;
    private final McpJsonMapper wire;

    public RawHttp(int port, JsonMapper mapper) {
        this.base = "http://127.0.0.1:" + port;
        this.mapper = mapper;
        this.wire = new JacksonMcpJsonMapper(mapper);
    }

    // ------------------------------------------------------------------------------------------- the requests

    public Reply get(String path, Header... headers) {
        return send(request(path, headers).GET().build());
    }

    public Reply delete(String path, Header... headers) {
        return send(request(path, headers).DELETE().build());
    }

    /** POSTs the text as it is (a body that is not a valid message is the point of some cases). */
    public Reply postText(String path, String body, Header... headers) {
        return send(request(path, headers).header("Content-Type", "application/json")
                .header("Accept", ACCEPTED_TYPES).POST(BodyPublishers.ofString(body)).build());
    }

    /**
     * POSTs the text with {@code Transfer-Encoding: chunked}: a publisher that does not know its length makes the JDK
     * client send no {@code Content-Length}.
     */
    public Reply postChunkedText(String path, String body, Header... headers) {
        return send(request(path, headers).header("Content-Type", "application/json")
                .header("Accept", ACCEPTED_TYPES).POST(BodyPublishers.fromPublisher(BodyPublishers.ofString(body)))
                .build());
    }

    /** The JSON text of a {@code tools/call} message, as {@link #callToolWithoutSession} writes it. */
    public String toolCallText(String tool, Object arguments) {
        try {
            return wire.writeValueAsString(new JSONRPCRequest(McpSchema.JSONRPC_VERSION,
                    McpSchema.METHOD_TOOLS_CALL, 2, callRequest(tool, arguments)));
        } catch (IOException failure) {
            throw new UncheckedIOException("the message cannot be written as JSON", failure);
        }
    }

    /** POSTs the SDK message written as JSON. */
    public Reply post(String path, Object message, Header... headers) {
        try {
            return postText(path, wire.writeValueAsString(message), headers);
        } catch (IOException failure) {
            throw new UncheckedIOException("the message cannot be written as JSON", failure);
        }
    }

    // ------------------------------------------------------------------------------------------- MCP messages

    /** {@code initialize} on {@code /mcp}. */
    public Reply initialize(Header... headers) {
        InitializeRequest params = InitializeRequest.builder(ProtocolVersions.MCP_2025_11_25,
                ClientCapabilities.builder().build(), Implementation.builder("raw-http-test", "1").build()).build();
        return post("/mcp", new JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_INITIALIZE, 1, params),
                headers);
    }

    /** The result of an {@code initialize} answer. */
    public InitializeResult initializeResult(Reply reply) {
        return wire.convertValue(response(reply).result(), InitializeResult.class);
    }

    /** {@code notifications/initialized}, which completes the handshake of a session. */
    public Reply initialized(String sessionId, Header... headers) {
        return post("/mcp", new JSONRPCNotification(McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_NOTIFICATION_INITIALIZED, null), withSession(sessionId, headers));
    }

    /** {@code tools/call} in the session. */
    public Reply callTool(String sessionId, String tool, Object arguments, Header... headers) {
        CallToolRequest params = callRequest(tool, arguments);
        return post("/mcp", new JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, 2, params),
                withSession(sessionId, headers));
    }

    /** {@code tools/list} in the session. */
    public Reply listTools(String sessionId, Header... headers) {
        return post("/mcp", new JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_LIST, 3, null),
                withSession(sessionId, headers));
    }

    /** {@code tools/call} without a session, as the bearer filter and the size check see it first. */
    public Reply callToolWithoutSession(String tool, Object arguments, Header... headers) {
        CallToolRequest params = callRequest(tool, arguments);
        return post("/mcp", new JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, 2, params),
                headers);
    }

    /** Opens a session: initialize with the headers, then the initialized notification; returns its id. */
    public String openSession(Header... headers) {
        Reply reply = initialize(headers);
        String id = reply.header(HttpHeaders.MCP_SESSION_ID).orElseThrow();
        initialized(id, headers);
        return id;
    }

    /** The JSON-RPC response in an answer. */
    public JSONRPCResponse response(Reply reply) {
        try {
            return (JSONRPCResponse) McpSchema.deserializeJsonRpcMessage(wire, reply.payload());
        } catch (IOException failure) {
            throw new UncheckedIOException("the answer is not a JSON-RPC response: " + reply.body(), failure);
        }
    }

    /** The result of a {@code tools/call} answer. */
    public McpSchema.CallToolResult toolResult(Reply reply) {
        return wire.convertValue(response(reply).result(), McpSchema.CallToolResult.class);
    }

    /** A value of the SDK (a structured content) as the record. */
    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    private CallToolRequest callRequest(String tool, Object arguments) {
        return CallToolRequest.builder(tool).arguments(wire, mapper.writeValueAsString(arguments)).build();
    }

    private static Header[] withSession(String sessionId, Header[] headers) {
        Header[] all = Arrays.copyOf(headers, headers.length + 1);
        all[headers.length] = Header.session(sessionId);
        return all;
    }

    // ------------------------------------------------------------------------------------------------ plumbing

    private HttpRequest.Builder request(String path, Header[] headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT);
        List.of(headers).forEach(header -> builder.header(header.name(), header.value()));
        return builder;
    }

    private Reply send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + request.uri(), failure);
        }
    }
}
