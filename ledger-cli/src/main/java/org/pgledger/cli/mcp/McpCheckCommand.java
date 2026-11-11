package org.pgledger.cli.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code mcp-check [--url=<url>]}: connects to a running ledger MCP server with the MCP Java SDK client (the SDK the server
 * runs on) over Streamable HTTP, lists its tools and checks that every tool of {@link ExpectedTools} is there; it prints
 * how many tools the server lists. The URL defaults to the local server ({@value #DEFAULT_URL}); it is
 * {@code scheme://host[:port][/path]} without user information, query or fragment, so that nothing secret can be part of
 * it. The bearer token is the setting {@code ledger.mcp.token} ({@link McpCheckProperties}: the secret store, or
 * {@code --ledger.mcp.token=<token>}). The command needs no database.
 *
 * <p>Every failure is exit code 1 with a message that never contains the token or the text of an exception of the client
 * (those can hold request headers): the token is missing, the URL cannot be reached, the server refused the request (a
 * wrong bearer token, or a host name that is not on its allow list: 401 or 403), the answer is not an MCP answer, or
 * tools are missing (their names are shown).
 *
 * <p>The client reports every failure of the transport as an unchecked exception whose type depends on the layer that
 * failed (the SDK, Reactor, the JDK HTTP client), so the command catches {@link RuntimeException} here and nowhere else in
 * the CLI, sorts the cause chain by type, and rethrows every case as a {@link FailureException}. The cause is not attached
 * (an exception of the client can carry request headers, and a stack trace would put them into a log); the message names
 * the types of the chain instead, so nothing is swallowed.
 */
@Component
public class McpCheckCommand implements Command {

    static final String URL = "url";

    /** The address the MCP server binds to by default (see its {@code server.address} and {@code server.port}). */
    static final String DEFAULT_URL = "http://127.0.0.1:8765/mcp";

    private static final String DEFAULT_PATH = "/mcp";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final McpCheckProperties properties;
    private final CliOutput output;
    private final CliMessages messages;

    McpCheckCommand(McpCheckProperties properties, CliOutput output, CliMessages messages) {
        this.properties = properties;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.MCP_CHECK;
    }

    @Override
    public Set<String> options() {
        return Set.of(URL);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        URI uri = endpoint(CliOptions.value(arguments, URL).orElse(DEFAULT_URL));
        String token = properties.token();
        if (token == null || token.isBlank()) {
            throw new FailureException(ErrorCode.MCP_TOKEN_MISSING);
        }
        String shown = uri.getScheme() + "://" + uri.getRawAuthority() + path(uri);
        List<String> listed = listTools(uri, token, shown);
        List<String> missing = ExpectedTools.all().stream().filter(name -> !listed.contains(name)).toList();
        if (!missing.isEmpty()) {
            throw new FailureException(ErrorCode.MCP_TOOL_MISSING, shown, String.join(", ", missing));
        }
        output.info(messages.get("mcp-check.ok", shown, String.valueOf(listed.size()),
                String.valueOf(ExpectedTools.all().size())));
    }

    /** The URL as given: http or https, a host, and nothing that could carry a secret. */
    private static URI endpoint(String text) {
        URI uri;
        try {
            uri = URI.create(text.strip());
        } catch (IllegalArgumentException notAUri) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean web = "http".equals(scheme) || "https".equals(scheme);
        if (!web || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        return uri;
    }

    private static String path(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() || "/".equals(path) ? DEFAULT_PATH : path;
    }

    private List<String> listTools(URI uri, String token, String shown) {
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(path(uri))
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .connectTimeout(TIMEOUT)
                .addHttpRequestCustomizer((request, method, target, body, context) ->
                        request.header("Authorization", "Bearer " + token))
                .build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(TIMEOUT).initializationTimeout(TIMEOUT)
                .build()) {
            client.initialize();
            List<String> names = new ArrayList<>();
            ListToolsResult page = client.listTools();
            names.addAll(page.tools().stream().map(Tool::name).toList());
            while (page.nextCursor() != null) {
                page = client.listTools(page.nextCursor());
                names.addAll(page.tools().stream().map(Tool::name).toList());
            }
            return names;
        } catch (RuntimeException failure) {
            throw translate(failure, shown);
        }
    }

    /** Sorts the failure of the client by the type found in its cause chain; no text of an exception is passed on. */
    private static FailureException translate(RuntimeException failure, String shown) {
        List<String> chain = new ArrayList<>();
        boolean refused = false;
        boolean unreachable = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause.getClass().getSimpleName());
            refused |= cause instanceof McpHttpClientTransportAuthorizationException;
            unreachable |= cause instanceof IOException || cause instanceof UnresolvedAddressException;
        }
        if (refused) {
            return new FailureException(ErrorCode.MCP_AUTHORIZATION_REFUSED, shown);
        }
        if (unreachable) {
            return new FailureException(ErrorCode.MCP_UNREACHABLE, shown);
        }
        return new FailureException(ErrorCode.MCP_FAILED, shown,
                chain.stream().collect(Collectors.joining(" <- ")));
    }
}
