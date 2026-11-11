package org.pgledger.cli.support;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real MCP server for the {@code mcp-check} tests: the MCP Java SDK server over Streamable HTTP at {@code /mcp}, served by an
 * embedded Tomcat on a free port of the loopback address, in this process. It speaks the protocol the product server speaks
 * (the same SDK, the same transport) and lists the tools it is given; a servlet filter answers 401 to every request that does
 * not carry the bearer token. The tool handlers are never called by {@code mcp-check}.
 */
public final class McpTestServer implements AutoCloseable {

    private static final String ENDPOINT = "/mcp";

    private final Tomcat tomcat;
    private final McpSyncServer server;
    private McpTestServer(Tomcat tomcat, McpSyncServer server) {
        this.tomcat = tomcat;
        this.server = server;
    }

    /**
     * Starts a server that lists these tools and accepts this bearer token.
     *
     * @param baseDirectory an empty directory of the test for Tomcat's working files
     */
    public static McpTestServer start(Path baseDirectory, String bearer, List<String> toolNames) {
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build())).mcpEndpoint(ENDPOINT).build();
        McpServer.StreamableSyncSpecification specification = McpServer.sync(transport);
        specification.serverInfo("ledger-test", "0").immediateExecution(true).validateToolInputs(false);
        for (String name : toolNames) {
            Tool tool = Tool.builder(name, Map.of("type", "object")).description("test tool " + name).build();
            specification.toolCall(tool, (exchange, request) -> CallToolResult.builder().addTextContent("ok").build());
        }
        McpSyncServer server = specification.build();

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDirectory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", baseDirectory.toString());
        org.apache.catalina.Wrapper wrapper = Tomcat.addServlet(context, "mcp", transport);
        wrapper.setAsyncSupported(true);
        context.addServletMappingDecoded("/*", "mcp");
        addFilter(context, bearerFilter(bearer));
        try {
            tomcat.start();
        } catch (LifecycleException failure) {
            throw new IllegalStateException("the test MCP server did not start", failure);
        }
        return new McpTestServer(tomcat, server);
    }

    private static void addFilter(Context context, Filter filter) {
        FilterDef definition = new FilterDef();
        definition.setFilterName("bearer");
        definition.setFilter(filter);
        definition.setAsyncSupported("true");
        context.addFilterDef(definition);
        FilterMap mapping = new FilterMap();
        mapping.setFilterName("bearer");
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);
    }

    /** The filter of the test server: 401 unless the request carries exactly {@code Authorization: Bearer <token>}. */
    private static Filter bearerFilter(String bearer) {
        byte[] expected = bearer.getBytes(StandardCharsets.UTF_8);
        return (request, response, chain) -> {
            String header = ((jakarta.servlet.http.HttpServletRequest) request).getHeader("Authorization");
            boolean accepted = header != null && header.startsWith("Bearer ")
                    && MessageDigest.isEqual(expected, header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
            if (accepted) {
                chain.doFilter(request, response);
            } else {
                HttpServletResponse denied = (HttpServletResponse) response;
                denied.setHeader("WWW-Authenticate", "Bearer");
                denied.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
        };
    }

    /** The URL of the MCP endpoint of this server. */
    public String url() {
        return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + ENDPOINT;
    }

    /** The port of this server (to find a port that is free afterwards, close the server first). */
    public int port() {
        return tomcat.getConnector().getLocalPort();
    }

    @Override
    public void close() {
        server.close();
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException failure) {
            throw new IllegalStateException("the test MCP server did not stop", failure);
        }
    }
}
