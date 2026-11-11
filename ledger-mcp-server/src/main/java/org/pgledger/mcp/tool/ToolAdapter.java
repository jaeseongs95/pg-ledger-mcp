package org.pgledger.mcp.tool;

/**
 * Marks a bean whose {@code @McpTool} methods are tools of this server. The catalog finds the tools through this
 * marker, binds their arguments and registers them, so a new tool is a new adapter bean and nothing else.
 */
public interface ToolAdapter {
}
