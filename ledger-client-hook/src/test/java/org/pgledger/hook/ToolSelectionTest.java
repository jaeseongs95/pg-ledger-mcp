package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pgledger.hook.HookHarness.ATTESTATION;
import static org.pgledger.hook.HookHarness.HOST_ID;
import static org.pgledger.hook.HookHarness.MAPPER;
import static org.pgledger.hook.HookHarness.WRITE_TOOLS;
import static org.pgledger.hook.HookHarness.event;
import static org.pgledger.hook.HookHarness.forwardedArguments;
import static org.pgledger.hook.HookHarness.outcome;
import static org.pgledger.hook.HookHarness.quote;
import static org.pgledger.hook.HookHarness.run;
import static org.pgledger.hook.HookHarness.toolName;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgledger.hook.HookHarness.Outcome;
import org.pgledger.hook.HookHarness.Run;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** HK-007, HK-011, HK-012, HK-013: which calls are stamped and which pass untouched. */
class ToolSelectionTest {

    private static final List<String> READ_OPS = List.of(
            "get", "history", "list", "search", "defects", "save_extra", "whoami_x", "Save", "SAVE", "xsave");

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-007 read tools, near misses, other tools and unprefixed names are not touched; the new read tools are never stamped")
    void leavesOtherToolsAlone(Client client) {
        String input = "{\"" + ATTESTATION + "\":\"model:value\"}";
        for (String op : READ_OPS) {
            Run run = run(client, event(client, toolName(client, op), input, "null")).ok();
            assertUntouched(client, run, op);
        }
        for (String name : List.of("run_command", "ledger_save", "ledger_get", "mcp__other__read", "mcp__other__save", "mcp__test__other_save", "Bash")) {
            Run run = run(client, event(client, name, input, "null")).ok();
            assertUntouched(client, run, name);
        }
    }

    private static void assertUntouched(Client client, Run run, String what) {
        assertThat(outcome(client, run)).as(what).isEqualTo(Outcome.UNTOUCHED);
        if (client == Client.CLAUDE) {
            assertThat(run.out().trim()).as(what).isEqualTo("{}");
        } else {
            assertThat(run.stdout()).as(what).isEmpty();
        }
    }

    @Test
    @DisplayName("HK-011 Antigravity MCP name candidates mcp:ledger_save, mcp:test:ledger_save, mcp:test/ledger_save, mcp:test_ledger_save are all stamped")
    void antigravityNameCandidates() {
        for (String name : List.of("mcp:ledger_save", "mcp:test:ledger_save", "mcp:test/ledger_save", "mcp:test_ledger_save")) {
            Run run = run(Client.ANTIGRAVITY, event(Client.ANTIGRAVITY, name, "{}")).ok();
            assertThat(forwardedArguments(Client.ANTIGRAVITY, "{}", run).path(ATTESTATION).asString()).as(name).isEqualTo("gemini:" + HOST_ID);
        }
        for (String name : List.of("mcp:xledger_save", "mcp:test:ledger_get", "ledger_save")) {
            assertThat(outcome(Client.ANTIGRAVITY, run(Client.ANTIGRAVITY, event(Client.ANTIGRAVITY, name, "{}")).ok())).as(name).isEqualTo(Outcome.UNTOUCHED);
        }
    }

    @Test
    @DisplayName("HK-013 Codex namespace mcp__codex_apps__app_ledger_ledger_<op>: all ten handled, read tool names are not")
    void codexAppNamespace() {
        for (String op : WRITE_TOOLS) {
            String name = "mcp__codex_apps__app_ledger_ledger_" + op;
            Run run = run(Client.CODEX, event(Client.CODEX, name, "{\"marker\":\"namespace\"}")).ok();
            assertThat(forwardedArguments(Client.CODEX, "{}", run).path(ATTESTATION).asString()).as(name).isEqualTo("codex:" + HOST_ID);
            Run reserved = run(Client.CODEX, event(Client.CODEX, name, "{\"" + ATTESTATION + "\":null}")).ok();
            assertThat(outcome(Client.CODEX, reserved)).as(name).isEqualTo(Outcome.DENIED);
        }
        for (String op : List.of("get", "history", "search", "defects")) {
            Run run = run(Client.CODEX, event(Client.CODEX, "mcp__codex_apps__app_ledger_ledger_" + op, "{}")).ok();
            assertThat(outcome(Client.CODEX, run)).as(op).isEqualTo(Outcome.UNTOUCHED);
        }
    }

    // ---- HK-012: call_mcp_tool envelope ----

    private static final String INPUT = "{\"marker\":\"wrapper\",\"author_id\":\"model:declared\",\"nested\":{\"keep\":[1,null,false]}}";

    private static String envelope(String toolName, String argumentsJson) {
        return "{\"ServerName\":\"t0001sink\",\"ToolName\":" + quote(toolName) + ",\"Arguments\":" + argumentsJson
                + ",\"extra\":{\"keep\":true}}";
    }

    private static Run wrapped(String envelopeJson) {
        return run(Client.ANTIGRAVITY, event(Client.ANTIGRAVITY, "call_mcp_tool", envelopeJson)).ok();
    }

    @Test
    @DisplayName("HK-012 call_mcp_tool: only Arguments is overwritten (object stays object, JSON string stays JSON string), envelope untouched")
    void wrapperArguments() {
        for (boolean asText : new boolean[] {false, true}) {
            String arguments = asText ? quote(INPUT) : INPUT;
            for (String op : WRITE_TOOLS) {
                String envelope = envelope("app_ledger_ledger_" + op, arguments);
                Run run = wrapped(envelope);
                assertThat(outcome(Client.ANTIGRAVITY, run)).isEqualTo(Outcome.STAMPED);
                JsonNode out = run.json();
                assertThat(out.path("overwrite").propertyNames()).containsExactly("Arguments");
                JsonNode sent = out.path("overwrite").path("Arguments");
                assertThat(sent.isString()).as("Arguments type kept (text=" + asText + ")").isEqualTo(asText);
                JsonNode inner = asText ? MAPPER.readTree(sent.stringValue()) : sent;
                assertThat(inner).isEqualTo(HookHarness.withAttestation(INPUT, "gemini:" + HOST_ID));
                // the envelope itself is not part of the overwrite: ServerName, ToolName and extra keys stay as the host has them
                assertThat(out.path("overwrite").has("ServerName")).isFalse();
                assertThat(out.path("overwrite").has("extra")).isFalse();
            }
        }
    }

    @Test
    @DisplayName("HK-012 call_mcp_tool: a reserved key in Arguments (either form) or on the envelope is denied")
    void wrapperReservedKey() {
        for (String value : List.of("null", "\"\"", "false", "{}", "[]", "\"gemini:" + HOST_ID + "\"")) {
            String inner = "{\"" + ATTESTATION + "\":" + value + "}";
            assertThat(outcome(Client.ANTIGRAVITY, wrapped(envelope("ledger_save", inner)))).as(value).isEqualTo(Outcome.DENIED);
            assertThat(outcome(Client.ANTIGRAVITY, wrapped(envelope("ledger_save", quote(inner))))).as(value).isEqualTo(Outcome.DENIED);
        }
        String onEnvelope = "{\"ServerName\":\"s\",\"ToolName\":\"ledger_save\",\"Arguments\":{},\"" + ATTESTATION + "\":1}";
        assertThat(outcome(Client.ANTIGRAVITY, wrapped(onEnvelope))).isEqualTo(Outcome.DENIED);
    }

    @Test
    @DisplayName("HK-012 call_mcp_tool: invalid Arguments (null, [], 7, {, \"text\", missing) are denied")
    void wrapperInvalidArguments() {
        for (String arguments : List.of("null", "[]", "7", quote("{"), quote("\"text\""), quote("[]"), quote("null"), quote(""), quote("{} {}"))) {
            Run run = wrapped(envelope("ledger_save", arguments));
            assertThat(outcome(Client.ANTIGRAVITY, run)).as(arguments).isEqualTo(Outcome.DENIED);
        }
        String missing = "{\"ServerName\":\"s\",\"ToolName\":\"ledger_save\"}";
        assertThat(outcome(Client.ANTIGRAVITY, wrapped(missing))).isEqualTo(Outcome.DENIED);
    }

    @Test
    @DisplayName("HK-012 call_mcp_tool: a non-ledger ToolName, a missing/empty ServerName or a non-object envelope passes")
    void wrapperPassThrough() {
        String reserved = "{\"" + ATTESTATION + "\":null}";
        for (String toolName : List.of("ledger_get", "unrelated_save", "ledger_save_extra", "ledger_history")) {
            assertThat(outcome(Client.ANTIGRAVITY, wrapped(envelope(toolName, reserved)))).as(toolName).isEqualTo(Outcome.UNTOUCHED);
        }
        for (String broken : List.of(
                "{\"ToolName\":\"ledger_save\",\"Arguments\":{}}",
                "{\"ServerName\":\"\",\"ToolName\":\"ledger_save\",\"Arguments\":{}}",
                "{\"ServerName\":3,\"ToolName\":\"ledger_save\",\"Arguments\":{}}",
                "{\"ServerName\":\"s\",\"ToolName\":7,\"Arguments\":{}}",
                "[]", "null", "\"text\"")) {
            assertThat(outcome(Client.ANTIGRAVITY, wrapped(broken))).as(broken).isEqualTo(Outcome.UNTOUCHED);
        }
    }

    @Test
    @DisplayName("HK-012 call_mcp_tool keeps unknown extra keys of Arguments and does not touch a stamped copy of the input")
    void wrapperUnknownFieldsSurvive() {
        String input = "{\"unknown\":{\"a\":[1,2,{\"b\":null}]},\"note\":\"한글\"}";
        Run run = wrapped(envelope("ledger_batch", input));
        JsonNode sent = run.json().path("overwrite").path("Arguments");
        ObjectNode expected = HookHarness.withAttestation(input, "gemini:" + HOST_ID);
        assertThat(sent).isEqualTo(expected);
    }
}
