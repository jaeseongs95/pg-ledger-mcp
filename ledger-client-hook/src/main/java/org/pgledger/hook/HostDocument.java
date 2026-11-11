package org.pgledger.hook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** The JSON document the hook writes to the host, serialised by Jackson; absent fields are left out. */
sealed interface HostDocument {

    /** {@code {}}: the host carries on unchanged. */
    record Empty() implements HostDocument {}

    /** Claude Code and Codex: {@code hookSpecificOutput}. */
    record PreToolUse(Specific hookSpecificOutput) implements HostDocument {

        /**
         * @param updatedInput the model's arguments with the reserved key: the tree of the original arguments
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Specific(String hookEventName, String permissionDecision, String permissionDecisionReason,
                JsonNode updatedInput) {}
    }

    /** Antigravity: {@code decision} with a {@code reason} (deny) or an {@code overwrite} (allow). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Decision(String decision, String reason, Overwrite overwrite) implements HostDocument {}

    /** What the host merges into its tool args. */
    sealed interface Overwrite {

        /** A direct call: only the reserved key. */
        record Attestation(@JsonProperty(Intake.RESERVED_KEY) String hostAttestation) implements Overwrite {}

        /** A wrapped call whose Arguments was an object. */
        record ObjectArguments(@JsonProperty("Arguments") JsonNode arguments) implements Overwrite {}

        /** A wrapped call whose Arguments was JSON text. */
        record TextArguments(@JsonProperty("Arguments") String arguments) implements Overwrite {}
    }
}
