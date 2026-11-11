package org.pgledger.hook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The Antigravity PreToolUse event, bound by Jackson. Fields of the wrong type are {@code null}; unknown fields are
 * ignored.
 */
record AntigravityEvent(ToolCall toolCall,
        @JsonDeserialize(using = LenientTextDeserializer.class) String conversationId) {

    /**
     * @param args the tool arguments of a direct call, or the {@link WrapperCall} of {@code call_mcp_tool}. The
     *             model's arguments are arbitrary JSON that must be given back unchanged except for the reserved
     *             key, so they stay a Jackson tree (see JsonFidelityTest).
     */
    record ToolCall(@JsonDeserialize(using = LenientTextDeserializer.class) String name, JsonNode args) {
    }
}
