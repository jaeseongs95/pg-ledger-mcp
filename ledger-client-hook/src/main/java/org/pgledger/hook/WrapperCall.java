package org.pgledger.hook;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The {@code args} of the Antigravity tool {@code call_mcp_tool}. Fields of the wrong type are {@code null};
 * unknown fields are ignored.
 *
 * @param arguments       the model's tool arguments, an object or the JSON text of an object; arbitrary JSON that
 *                        stays a Jackson tree so it can be given back unchanged except for the reserved key
 * @param hostAttestation the reserved key written by someone else: {@code null} if absent, whatever it holds
 *                        (even JSON null) if present
 */
record WrapperCall(
        @JsonProperty("ServerName") @JsonDeserialize(using = LenientTextDeserializer.class) String serverName,
        @JsonProperty("ToolName") @JsonDeserialize(using = LenientTextDeserializer.class) String toolName,
        @JsonProperty("Arguments") JsonNode arguments,
        @JsonProperty(Intake.RESERVED_KEY) JsonNode hostAttestation) {
}
