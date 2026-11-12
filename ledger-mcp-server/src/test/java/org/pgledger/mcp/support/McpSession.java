package org.pgledger.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.tool.ReadOutputs;
import org.springframework.context.ApplicationContext;
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
    private final Attester attester;

    private McpSession(int port, String bearer, JsonMapper mapper, Attester attester) {
        this.mapper = mapper;
        this.attester = attester;
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

    /** Opens a session on the server at the port, presenting the token. Its calls are sent as they are written (no hook). */
    public static McpSession open(int port, String bearer, JsonMapper mapper) {
        return new McpSession(port, bearer, mapper, Attester.none());
    }

    /**
     * Opens a session on the server of the context with the test token. Like the client of an author whose host runs the
     * hook, it signs the plain author of a write call (see {@link Attester}).
     */
    public static McpSession open(ApplicationContext context) {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return new McpSession(port, McpTestSettings.TOKEN, context.getBean(JsonMapper.class), Attester.of(context));
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

    /** Calls a tool with the arguments written by Jackson from the record; a plain author is signed first (see {@link Attester}). */
    public CallToolResult call(String tool, Object arguments) {
        return callUnsigned(tool, attester.stamp(tool, arguments));
    }

    /** Calls a tool with exactly these arguments: nothing is signed, nothing is added. */
    public CallToolResult callUnsigned(String tool, Object arguments) {
        return client.callTool(CallToolRequest.builder(tool).arguments(wire, mapper.writeValueAsString(arguments)).build());
    }

    /** Calls a tool that must succeed and reads its result into the record. */
    public <T> T ok(String tool, Object arguments, Class<T> type) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s failed: %s", tool, result.content()).isFalse();
        return read(result, type);
    }

    /** Calls a tool that must fail as a tool (isError) and returns its error. */
    public ToolError failed(String tool, Object arguments) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as("%s must fail, got %s", tool, result.content()).isTrue();
        return error(result);
    }

    /** The structured content of a result as the record. */
    public <T> T read(CallToolResult result, Class<T> type) {
        return mapper.convertValue(result.structuredContent(), type);
    }

    /** The structured content of a list result as a page of the item records. */
    public <T> ReadOutputs.Items<T> readItems(CallToolResult result, Class<T> item) {
        return mapper.convertValue(result.structuredContent(),
                mapper.getTypeFactory().constructParametricType(ReadOutputs.Items.class, item));
    }

    /** The text of the first content of a result. */
    public static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    /** The error of a failed call. */
    public ToolError error(CallToolResult result) {
        return read(result, ToolError.class);
    }

    /** The next tool list that arrived with list_changed, or null when none arrives in the time. */
    public List<Tool> nextListChange(Duration within) throws InterruptedException {
        return listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** The next tool list that arrived with list_changed; the test fails when none arrives in the time. */
    public List<Tool> awaitListChange(Duration within) {
        try {
            List<Tool> tools = listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS);
            assertThat(tools).as("a list_changed notification within %s", within).isNotNull();
            return tools;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for list_changed", failure);
        }
    }

    /**
     * Takes (and drops) the notifications that are still on their way to this new session, until none arrives for the
     * time: the code change of an earlier test of the same server is announced by the next poll, which can fall into the
     * first moments of the next test. A server that keeps announcing is a failure, not something to wait out.
     */
    public void drainListChanges(Duration quiet) {
        try {
            for (int announcements = 0; listChanges.poll(quiet.toNanos(), TimeUnit.NANOSECONDS) != null; announcements++) {
                assertThat(announcements).as("list_changed notifications that keep coming without a change").isLessThan(10);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for silence", failure);
        }
    }

    /** Waits the time and fails when a list_changed notification arrives in it. */
    public void awaitNoListChange(Duration within) {
        try {
            assertThat(listChanges.poll(within.toNanos(), TimeUnit.NANOSECONDS))
                    .as("no list_changed notification within %s", within).isNull();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for silence", failure);
        }
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
