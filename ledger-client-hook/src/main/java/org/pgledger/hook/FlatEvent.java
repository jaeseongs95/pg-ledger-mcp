package org.pgledger.hook;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The PreToolUse event of Claude Code and Codex, bound by Jackson. Fields of the wrong type are {@code null};
 * unknown fields are ignored.
 *
 * @param toolInput the model's tool arguments. Arbitrary JSON the hook must give back unchanged except for the
 *                  reserved key, so this one field stays a Jackson tree (see JsonFidelityTest); {@code null} if
 *                  the field is missing, a {@code NullNode} if it is JSON null
 */
record FlatEvent(
        @JsonProperty("tool_name") @JsonDeserialize(using = LenientTextDeserializer.class) String toolName,
        @JsonProperty("tool_input") JsonNode toolInput,
        @JsonProperty("session_id") @JsonDeserialize(using = LenientTextDeserializer.class) String sessionId) {
}
