package org.pgledger.mcp.error;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the result of a tool call: the output record as structured content and as its JSON text, or, when the call
 * fails, the {@link ToolError} as structured content with the text {@code <TOKEN>: <message>} and
 * {@code isError=true}. {@link #run} is the one entry of the MCP boundary that catches a failure of a tool.
 */
@Component
public class ToolResults {

    private final JsonMapper mapper;
    private final McpJsonMapper wire;
    private final ToolErrorMapper errors;

    public ToolResults(JsonMapper mapper, ToolErrorMapper errors) {
        this.mapper = mapper;
        this.wire = new JacksonMcpJsonMapper(mapper);
        this.errors = errors;
    }

    public CallToolResult run(Supplier<Object> call) {
        try {
            return success(call.get());
        } catch (RuntimeException failure) {
            return failure(errors.map(failure));
        }
    }

    /** The output is written once; the structured content and the text are the same JSON (nulls included). */
    private CallToolResult success(Object output) {
        String json = mapper.writeValueAsString(output);
        return CallToolResult.builder().structuredContent(wire, json).addTextContent(json).isError(false).build();
    }

    private CallToolResult failure(ToolError error) {
        return CallToolResult.builder().structuredContent(wire, mapper.writeValueAsString(error))
                .addTextContent(error.code() + ": " + error.message()).isError(true).build();
    }
}
