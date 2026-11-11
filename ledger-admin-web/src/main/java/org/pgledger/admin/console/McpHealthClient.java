package org.pgledger.admin.console;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Asks the MCP server whether it is up: one {@code GET} of exactly the configured health URL (never a URL from a
 * request) with a Spring {@code RestClient}, connect and read time limits from the configuration, and nothing secret
 * in the request. Whatever happens, the answer is a {@link McpStatus}; the page still renders.
 */
@Component
public class McpHealthClient {

    /** The body of the health endpoint; only the status is read. */
    record HealthBody(String status) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(McpHealthClient.class);

    private final URI url;
    private final RestClient client;

    public McpHealthClient(LedgerAdminProperties properties, RestClient.Builder builder) {
        String configured = properties.mcp().healthUrl();
        this.url = configured == null || configured.isBlank() ? null : URI.create(configured);
        Duration timeout = properties.mcp().timeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.client = builder.requestFactory(factory).build();
    }

    public McpStatus status() {
        if (url == null) {
            return new McpStatus(McpStatus.State.NOT_CONFIGURED, McpStatus.Reason.NONE);
        }
        try {
            HealthBody body = client.get().uri(url).retrieve().body(HealthBody.class);
            return body != null && "UP".equals(body.status())
                    ? new McpStatus(McpStatus.State.UP, McpStatus.Reason.NONE)
                    : new McpStatus(McpStatus.State.DOWN, McpStatus.Reason.REPORTS_DOWN);
        } catch (ResourceAccessException e) {
            LOG.warn("the MCP health check could not reach the server", e);
            return new McpStatus(McpStatus.State.DOWN,
                    e.getCause() instanceof HttpTimeoutException ? McpStatus.Reason.TIMEOUT
                            : McpStatus.Reason.UNREACHABLE);
        } catch (RestClientResponseException e) {
            LOG.warn("the MCP health check got an error status", e);
            return new McpStatus(McpStatus.State.DOWN, McpStatus.Reason.HTTP_ERROR);
        } catch (RestClientException e) {
            LOG.warn("the MCP health check got an answer that is not a health body", e);
            return new McpStatus(McpStatus.State.DOWN, McpStatus.Reason.INVALID_ANSWER);
        }
    }
}
