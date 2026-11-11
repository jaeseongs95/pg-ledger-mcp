package org.pgledger.mcp.support;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.pgledger.mcp.error.ToolError;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real MCP Java SDK client of the server over Streamable HTTP, with the bearer token and a queue of the tool lists
 * that arrived with {@code notifications/tools/list_changed}. Calls are made from records (Jackson writes the
 * arguments) and results are read back into records.
 */
public final class McpSession implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final McpSyncClient client;
    private final JsonMapper mapper;
    private final McpJsonMapper wire;
    private final BlockingQueue<List<Tool>> listChanges = new LinkedBlockingQueue<>();

    private McpSession(int port, String bearer, JsonMapper mapper) {
        this.mapper = mapper;
        this.wire = new JacksonMcpJsonMapper(mapper);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port).endpoint("/mcp").jsonMapper(wire)
                .addHttpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + bearer))
                .build();
        this.client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT)
                .toolsChangeConsumer(listChanges::add).build();
        this.client.initialize();
    }

    /** Opens a session on the server at the port, presenting the token. */
    public static McpSession open(int port, String bearer, JsonMapper mapper) {
        return new McpSession(port, bearer, mapper);
    }

    public McpSyncClient client() {
        return client;
    }

    public JsonMapper mapper() {
        return mapper;
    }

    public List<Tool> tools() {
        return client.listTools().tools();
    }

    public Tool tool(String name) {
        return tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    /** Calls a tool with the arguments written by Jackson from the record. */
    public CallToolResult call(String tool, Object arguments) {
        return client.callTool(CallToolRequest.builder(tool).arguments(wire, mapper.writeValueAsString(arguments)).build());
    }

    /** The structured content of a result as the record. */
    public <T> T read(CallToolResult result, Class<T> type) {
        return mapper.convertValue(result.structuredContent(), type);
    }

    /** The error of a failed call. */
    public ToolError error(CallToolResult result) {
        return read(result, ToolError.class);
    }

    /** The next tool list that arrived with list_changed, or null when none arrives in the time. */
    public List<Tool> nextListChange(Duration within) throws InterruptedException {
        return listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** How many list_changed tool lists are waiting. */
    public int pendingListChanges() {
        return listChanges.size();
    }

    @Override
    public void close() {
        client.close();
    }
}
