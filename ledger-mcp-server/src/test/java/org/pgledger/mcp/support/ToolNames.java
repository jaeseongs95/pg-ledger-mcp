package org.pgledger.mcp.support;

import java.util.List;
import java.util.stream.Stream;
import org.pgledger.domain.rule.ToolContract;

/**
 * The tools of the contract (catalogue sections 7 and 8): the names are part of the external interface. They are the list
 * of {@link ToolContract}, which the CLI command {@code mcp-check} also requires; the catalogue tests compare this list with
 * the tools of the real server, so the two cannot drift apart unnoticed.
 */
public final class ToolNames {

    public static final List<String> WRITE = ToolContract.WRITE;

    public static final List<String> READ = ToolContract.READ;

    private ToolNames() {
    }

    public static List<String> all() {
        return ToolContract.all();
    }

    public static Stream<String> write() {
        return WRITE.stream();
    }

    public static Stream<String> read() {
        return READ.stream();
    }
}
