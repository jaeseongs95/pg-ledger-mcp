package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared test support: runs the hook in process and builds host events and expected outputs per client. */
final class HookHarness {

    static final List<String> WRITE_TOOLS = List.of(
            "save", "transition", "record_fix", "record_case", "link", "correct", "batch", "review", "review_correct", "whoami");
    static final String HOST_ID = "parent-1234_ab.cd";
    static final String ATTESTATION = "_hostAttestation";

    /** A reader that is independent of the hook's own codec: exact numbers, trailing zeros kept. */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private HookHarness() {}

    /** What the hook did with a call, in terms common to the three host dialects. */
    enum Outcome { STAMPED, DENIED, UNTOUCHED }

    record Run(int exit, byte[] stdout, byte[] stderr) {
        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String err() {
            return new String(stderr, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            return MAPPER.readTree(stdout);
        }

        /** Exit 0, nothing on stderr (HK-016). */
        Run ok() {
            assertThat(err()).as("stderr").isEmpty();
            assertThat(exit).as("exit code").isZero();
            return this;
        }
    }

    static Run run(Client client, String stdin) {
        return run(new String[] {client.argument()}, stdin.getBytes(StandardCharsets.UTF_8));
    }

    static Run run(String[] args, byte[] stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            int exit = new HookCli(JsonCodec.create()).run(args, new ByteArrayInputStream(stdin), out, err);
            return new Run(exit, out.toByteArray(), err.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String toolName(Client client, String op) {
        return switch (client) {
            case CLAUDE -> "mcp__ledger__ledger_" + op;
            case CODEX -> "mcp__test__ledger_" + op;
            case ANTIGRAVITY -> "mcp:test:ledger_" + op;
        };
    }

    static String vendor(Client client) {
        return switch (client) {
            case CLAUDE -> "claude";
            case CODEX -> "codex";
            case ANTIGRAVITY -> "gemini";
        };
    }

    static String quote(String text) {
        return MAPPER.writeValueAsString(text);
    }

    /** A host event for {@code toolName} with raw JSON {@code inputJson}; {@code idJson} null omits the id key. */
    static String event(Client client, String toolName, String inputJson, String idJson) {
        return switch (client) {
            case CLAUDE, CODEX -> "{" + (idJson == null ? "" : "\"session_id\":" + idJson + ",")
                    + "\"agent_id\":\"child-ignored\",\"tool_name\":" + quote(toolName) + ",\"tool_input\":" + inputJson + "}";
            case ANTIGRAVITY -> "{\"toolCall\":{\"name\":" + quote(toolName) + ",\"args\":" + inputJson + "}"
                    + (idJson == null ? "" : ",\"conversationId\":" + idJson) + ",\"agent_id\":\"child-9999\"}";
        };
    }

    static String event(Client client, String toolName, String inputJson) {
        return event(client, toolName, inputJson, quote(HOST_ID));
    }

    static Outcome outcome(Client client, Run run) {
        if (run.stdout().length == 0) {
            return Outcome.UNTOUCHED;
        }
        JsonNode out = run.json();
        return switch (client) {
            case CLAUDE -> {
                JsonNode specific = out.path("hookSpecificOutput");
                if ("deny".equals(specific.path("permissionDecision").asString(""))) {
                    yield Outcome.DENIED;
                }
                yield specific.has("updatedInput") ? Outcome.STAMPED : Outcome.UNTOUCHED;
            }
            case CODEX -> switch (out.path("hookSpecificOutput").path("permissionDecision").asString("")) {
                case "deny" -> Outcome.DENIED;
                case "allow" -> Outcome.STAMPED;
                default -> Outcome.UNTOUCHED;
            };
            case ANTIGRAVITY -> switch (out.path("decision").asString("")) {
                case "deny" -> Outcome.DENIED;
                case "allow" -> Outcome.STAMPED;
                default -> Outcome.UNTOUCHED;
            };
        };
    }

    /** The arguments the host will forward after the hook: updatedInput, or the original merged with the overwrite. */
    static JsonNode forwardedArguments(Client client, String inputJson, Run run) {
        JsonNode out = run.json();
        return switch (client) {
            case CLAUDE, CODEX -> out.path("hookSpecificOutput").path("updatedInput");
            case ANTIGRAVITY -> {
                ObjectNode merged = ((ObjectNode) MAPPER.readTree(inputJson)).deepCopy();
                merged.setAll((ObjectNode) out.path("overwrite"));
                yield merged;
            }
        };
    }

    /**
     * An event whose whole input comes back in the answer: Claude and Codex always echo it, Antigravity only
     * for a {@code call_mcp_tool} wrapper (a direct MCP call overwrites just the one key), so it is used wrapped here.
     */
    static String echoingEvent(Client client, String op, String inputJson) {
        return switch (client) {
            case CLAUDE, CODEX -> event(client, toolName(client, op), inputJson);
            case ANTIGRAVITY -> event(client, "call_mcp_tool",
                    "{\"ServerName\":\"test\",\"ToolName\":\"ledger_" + op + "\",\"Arguments\":" + inputJson + "}");
        };
    }

    /** The complete arguments of an {@link #echoingEvent} answer. */
    static JsonNode echoedArguments(Client client, Run run) {
        JsonNode out = run.json();
        return switch (client) {
            case CLAUDE, CODEX -> out.path("hookSpecificOutput").path("updatedInput");
            case ANTIGRAVITY -> out.path("overwrite").path("Arguments");
        };
    }

    static ObjectNode withAttestation(String inputJson, String value) {
        ObjectNode copy = ((ObjectNode) MAPPER.readTree(inputJson)).deepCopy();
        copy.put(ATTESTATION, value);
        return copy;
    }

    static String denialText(Client client, Run run) {
        JsonNode out = run.json();
        return switch (client) {
            case CLAUDE, CODEX -> out.path("hookSpecificOutput").path("permissionDecisionReason").asString("");
            case ANTIGRAVITY -> out.path("reason").asString("");
        };
    }
}
