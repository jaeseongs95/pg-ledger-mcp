package org.pgledger.cli.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bearer token that {@code mcp-check} presents: the setting {@code ledger.mcp.token}, the same key the MCP server reads
 * its token from, so on the server host it comes from the secret store (a configtree property source, stored with
 * {@code secret-set ledger.mcp.token}); it can also be given as the Spring property {@code --ledger.mcp.token=<token>}.
 * It is not checked by Bean Validation (Boot prints the rejected value of a binding violation, and this one is a secret;
 * the command checks it and names only the key), and it never appears in a text made from this record.
 *
 * @param token the bearer token of the agents, or absent
 */
@ConfigurationProperties("ledger.mcp")
public record McpCheckProperties(String token) {

    /** The token is a secret: it never appears in a log line or a failure text made from this record. */
    @Override
    public String toString() {
        return "McpCheckProperties[token=<hidden>]";
    }
}
