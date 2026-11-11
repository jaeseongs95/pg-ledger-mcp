package org.pgledger.mcp.error;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the result of a tool call: the output record as structured content and as its JSON text, or, when the call
 * fails, the {@link ToolError} as structured content with the text {@code <TOKEN>: <message>} and
 * {@code isError=true}. {@link #run} is the one entry of the MCP boundary that catches a failure of a tool.
 *
 * <p>The structured content is handed to the transport as the object itself: the MCP SDK serializes it once, when
 * it writes the response, and nothing is parsed again on the way. The output records carry
 * {@code @JsonInclude(ALWAYS)}, so a null stays a null whatever default inclusion the transport mapper has. The
 * JSON text of a successful result is the one serialization this class makes: the MCP specification (2025-06-18,
 * server/tools, "Structured Content") recommends that a tool that returns structured content also returns the
 * serialized JSON in a text content block. Source: MCP specification 2025-06-18, server/tools, section "Structured
 * Content" (https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content): "For
 * backwards compatibility, a tool that returns structured content SHOULD also return the serialized JSON in a
 * TextContent block." (confirmed by reading the page).
 */
@Component
public class ToolResults {

    private final JsonMapper mapper;
    private final ToolErrorMapper errors;

    public ToolResults(JsonMapper mapper, ToolErrorMapper errors) {
        this.mapper = mapper;
        this.errors = errors;
    }

    public CallToolResult run(Supplier<Object> call) {
        try {
            return success(call.get());
        } catch (RuntimeException failure) {
            return failure(errors.map(failure));
        }
    }

    private CallToolResult success(Object output) {
        return CallToolResult.builder().structuredContent(output).addTextContent(mapper.writeValueAsString(output))
                .isError(false).build();
    }

    private CallToolResult failure(ToolError error) {
        return CallToolResult.builder().structuredContent(error).addTextContent(error.code() + ": " + error.message())
                .isError(true).build();
    }
}
