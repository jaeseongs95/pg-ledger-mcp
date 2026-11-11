package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pgledger.hook.HookHarness.ATTESTATION;
import static org.pgledger.hook.HookHarness.HOST_ID;
import static org.pgledger.hook.HookHarness.WRITE_TOOLS;
import static org.pgledger.hook.HookHarness.event;
import static org.pgledger.hook.HookHarness.forwardedArguments;
import static org.pgledger.hook.HookHarness.outcome;
import static org.pgledger.hook.HookHarness.quote;
import static org.pgledger.hook.HookHarness.run;
import static org.pgledger.hook.HookHarness.toolName;
import static org.pgledger.hook.HookHarness.vendor;
import static org.pgledger.hook.HookHarness.withAttestation;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.hook.HookHarness.Outcome;
import org.pgledger.hook.HookHarness.Run;
import tools.jackson.databind.JsonNode;

/** HK-001..HK-006, HK-009: the stamping rules, identical for the three clients. */
class StampingTest {

    private static final String NESTED_INPUT =
            "{\"project\":\"TEST\",\"nested\":{\"items\":[1,null,false]},\"note\":\"한글\",\"__proto__\":{\"sentinel\":true},\"author_id\":\"other:declared\"}";

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-001 all ten write tools: stamp with <vendor>:<host id>, every other field unchanged, host dialect output shape")
    void stampsAllWriteTools(Client client) {
        for (String op : WRITE_TOOLS) {
            Run run = run(client, event(client, toolName(client, op), NESTED_INPUT)).ok();
            assertThat(outcome(client, run)).as(op).isEqualTo(Outcome.STAMPED);
            assertThat(forwardedArguments(client, NESTED_INPUT, run))
                    .as(op)
                    .isEqualTo(withAttestation(NESTED_INPUT, vendor(client) + ":" + HOST_ID));
            assertOutputShape(client, run);
        }
    }

    private static void assertOutputShape(Client client, Run run) {
        JsonNode out = run.json();
        switch (client) {
            case CLAUDE -> {
                // Claude: updatedInput only, no permission decision, so the host's own permission policy still applies.
                assertThat(out.propertyNames()).containsExactly("hookSpecificOutput");
                assertThat(out.path("hookSpecificOutput").propertyNames()).containsExactly("hookEventName", "updatedInput");
                assertThat(out.path("hookSpecificOutput").path("hookEventName").asString()).isEqualTo("PreToolUse");
            }
            case CODEX -> {
                assertThat(out.propertyNames()).containsExactly("hookSpecificOutput");
                assertThat(out.path("hookSpecificOutput").propertyNames())
                        .containsExactlyInAnyOrder("hookEventName", "permissionDecision", "updatedInput");
                assertThat(out.path("hookSpecificOutput").path("hookEventName").asString()).isEqualTo("PreToolUse");
            }
            case ANTIGRAVITY -> {
                assertThat(out.propertyNames()).containsExactlyInAnyOrder("decision", "overwrite");
                assertThat(out.path("overwrite").propertyNames()).containsExactly(ATTESTATION);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-002 an absent author_id is not created")
    void doesNotCreateAuthorId(Client client) {
        String input = "{\"project\":\"TEST\"}";
        Run run = run(client, event(client, toolName(client, "whoami"), input)).ok();
        assertThat(forwardedArguments(client, input, run).has("author_id")).isFalse();
        assertThat(forwardedArguments(client, input, run).path(ATTESTATION).asString()).isEqualTo(vendor(client) + ":" + HOST_ID);
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-003 an existing author_id (cached connector schema) is kept and never copied into _hostAttestation")
    void keepsExistingAuthorId(Client client) {
        for (String op : WRITE_TOOLS) {
            String input = "{\"author_id\":\"other:declared\",\"marker\":\"cached-schema\"}";
            Run run = run(client, event(client, toolName(client, op), input)).ok();
            JsonNode forwarded = forwardedArguments(client, input, run);
            assertThat(forwarded.path("author_id").asString()).isEqualTo("other:declared");
            assertThat(forwarded.path(ATTESTATION).asString()).isEqualTo(vendor(client) + ":" + HOST_ID);
        }
    }

    static Stream<String> reservedValues() {
        return Stream.of("null", "\"\"", "false", "0", "{}", "[]", "\"" + "VENDOR:" + HOST_ID + "\"", "\"other:forged\"");
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-004 a model-supplied _hostAttestation of any value is denied as reserved, nothing is rewritten")
    void deniesReservedKey(Client client) {
        for (String raw : (Iterable<String>) reservedValues()::iterator) {
            String value = raw.replace("VENDOR", vendor(client));
            for (String op : WRITE_TOOLS) {
                String input = "{\"" + ATTESTATION + "\":" + value + "}";
                Run run = run(client, event(client, toolName(client, op), input)).ok();
                assertThat(outcome(client, run)).as(op + " " + value).isEqualTo(Outcome.DENIED);
                assertThat(HookHarness.denialText(client, run)).contains("reserved");
                assertThat(run.out()).doesNotContain("updatedInput").doesNotContain("overwrite");
            }
        }
    }

    static List<String> invalidIds() {
        return List.of(
                "null", "\"\"", "3", "{}", "[]", "true", quote("x".repeat(129)), quote("white space"), quote(" "),
                quote("other:id"), quote("bad:id"), quote("x\n"), quote("x\r\n"), quote("x\r"), quote("한글"), quote("a/b"));
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-005 a missing or invalid host id is denied, with no fallback to an agent id or author_id")
    void deniesInvalidHostIds(Client client) {
        String input = "{\"author_id\":\"" + vendor(client) + ":fallback\"}";
        for (String id : invalidIds()) {
            Run run = run(client, event(client, toolName(client, "save"), input, id)).ok();
            assertThat(outcome(client, run)).as(id).isEqualTo(Outcome.DENIED);
            assertThat(run.out()).doesNotContain("fallback").doesNotContain("child-");
        }
        Run missing = run(client, event(client, toolName(client, "whoami"), "{}", null)).ok();
        assertThat(outcome(client, missing)).isEqualTo(Outcome.DENIED);
    }

    static Stream<String> validIds() {
        return Stream.of("x", "a._-Z9", "x".repeat(128));
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-006 ids of 1 char, the full character set and 128 chars are used as they are")
    void acceptsValidIds(Client client) {
        for (String id : (Iterable<String>) validIds()::iterator) {
            Run run = run(client, event(client, toolName(client, "whoami"), "{}", quote(id))).ok();
            assertThat(forwardedArguments(client, "{}", run).path(ATTESTATION).asString()).isEqualTo(vendor(client) + ":" + id);
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-009 each invocation uses the id it is given (A, B, A), no state between runs")
    void keepsNoStateBetweenRuns(Client client) {
        for (String id : List.of("parent-A", "parent-B", "parent-A")) {
            Run run = run(client, event(client, toolName(client, "save"), "{}", quote(id))).ok();
            assertThat(forwardedArguments(client, "{}", run).path(ATTESTATION).asString()).isEqualTo(vendor(client) + ":" + id);
        }
    }

    @ParameterizedTest
    @MethodSource("clientVendors")
    @DisplayName("HK-020 the vendor prefix of the stamped id follows the client enum (claude:, codex:, gemini:)")
    void vendorPrefixFollowsClient(Client client, String prefix) {
        Run run = run(client, event(client, toolName(client, "whoami"), "{}")).ok();
        assertThat(forwardedArguments(client, "{}", run).path(ATTESTATION).asString()).isEqualTo(prefix + ":" + HOST_ID);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> clientVendors() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(Client.CLAUDE, "claude"),
                org.junit.jupiter.params.provider.Arguments.of(Client.CODEX, "codex"),
                org.junit.jupiter.params.provider.Arguments.of(Client.ANTIGRAVITY, "gemini"));
    }
}
