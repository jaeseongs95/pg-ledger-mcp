package org.pgledger.mcp.support;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.pgledger.mcp.tool.ToolAdapter;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tool adapters of the tests, registered at run time with {@code ToolCatalog.register}. They are real adapters (the
 * same kind of class as the tools of the server), not stand-ins: one that works and two that fail in the way a defect
 * or the database fails, with texts that must never reach a model.
 */
public final class ProbeTools {

    /** The text that an unexpected failure carries and that no result may contain. */
    public static final String SECRET_DETAIL = "boom: SELECT secret FROM vault";

    /** The text of a database failure that no result may contain. */
    public static final String SQL_DETAIL = "could not execute statement [insert into history values (?)] HISTORY_PK";

    private ProbeTools() {
    }

    /** What the working probe tool answers. */
    public record Pong(@JsonProperty("answer") String answer) {
    }

    /** A tool that works. */
    public static class Working implements ToolAdapter {

        @McpTool(name = "probe_ping", description = "Answers pong. A tool added while the server runs.",
                generateOutputSchema = true)
        public Pong ping() {
            return new Pong("pong");
        }
    }

    /** A tool whose method throws a RuntimeException. */
    public static class Defective implements ToolAdapter {

        @McpTool(name = "probe_defect", description = "Fails with an unexpected exception.",
                generateOutputSchema = true)
        public Pong fail() {
            throw new IllegalStateException(SECRET_DETAIL);
        }
    }

    /** A tool whose method throws the exception of a database integrity violation. */
    public static class Violating implements ToolAdapter {

        @McpTool(name = "probe_integrity", description = "Fails with a database integrity violation.",
                generateOutputSchema = true)
        public Pong fail() {
            throw new DataIntegrityViolationException(SQL_DETAIL);
        }
    }
}
