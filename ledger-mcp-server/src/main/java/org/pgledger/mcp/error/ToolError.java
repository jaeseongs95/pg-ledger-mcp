package org.pgledger.mcp.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The structured content of a failed tool call: the error token of the contract, the message in English and the
 * context. {@code details} is the context of the failure (for example the valid codes); {@code issues} lists what
 * is wrong with each argument when the call was refused as invalid.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ToolError(@JsonProperty("code") String code, @JsonProperty("message") String message,
        @JsonProperty("details") Map<String, List<String>> details, @JsonProperty("issues") List<Issue> issues) {

    public ToolError {
        details = Map.copyOf(details);
        issues = List.copyOf(issues);
    }

    /** One thing wrong with an argument: where, why (in English) and the allowed values when the set is closed. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Issue(@JsonProperty("path") String path, @JsonProperty("reason") String reason,
            @JsonProperty("allowed") List<String> allowed) {

        public Issue {
            allowed = List.copyOf(allowed);
        }
    }
}
