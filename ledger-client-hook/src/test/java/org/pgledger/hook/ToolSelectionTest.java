package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.hook.AntigravityHost.Envelope;
import tools.jackson.databind.JsonNode;

/** HK-007, HK-011, HK-012, HK-013: which calls are stamped and which pass untouched. */
@ExtendWith(HookExtension.class)
class ToolSelectionTest {

    private static final AntigravityHost AGY = AntigravityHost.INSTANCE;
    private static final CodexHost CODEX = new CodexHost();
    private static final String ID = HookFixture.HOST_ID;
    private static final JsonNode EMPTY = Json.of("{}");

    private static final List<String> READ_OPS = List.of(
            "get", "history", "list", "search", "defects", "save_extra", "whoami_x", "Save", "SAVE", "xsave");
    private static final List<String> OTHER_NAMES = List.of(
            "run_command", "ledger_save", "ledger_get", "mcp__other__read", "mcp__other__save", "mcp__test__other_save", "Bash");

    /** Names the hook must not touch: read tools in the naming of each host, and names of other tools. */
    static Stream<Arguments> untouchedNames() {
        return Hosts.ALL.stream().flatMap(host -> Stream.concat(
                        READ_OPS.stream().map(host::toolName), OTHER_NAMES.stream())
                .map(name -> Arguments.argumentSet(host + " " + name, host, name)));
    }

    @ParameterizedTest
    @MethodSource("untouchedNames")
    @DisplayName("HK-007 read tools, near misses, other tools and unprefixed names are not touched (Claude answers {}, the others stay silent)")
    void leavesOtherToolsAlone(HostScenario host, String name, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of(Json.RESERVED_KEY, "model:value"));
        Run run = fixture.runClean(host, host.event(name, input, Json.of("null")));
        assertThat(run.document()).isEqualTo(host.untouched());
    }

    static Stream<Arguments> antigravityNames() {
        return Stream.of(
                Arguments.of("mcp:test:ledger_save", "test"), Arguments.of("mcp:test/ledger_save", "test"),
                Arguments.of("mcp:test_ledger_save", "test"), Arguments.of("mcp:ledger_ledger_save", "ledger"),
                Arguments.of("mcp:test:test_ledger_save", "test"), Arguments.of("mcp:my.server/ledger_save", "my.server"));
    }

    @ParameterizedTest
    @MethodSource("antigravityNames")
    @DisplayName("HK-011 Antigravity MCP names mcp:<server><:|/|_>[<server>_]ledger_save are stamped, for the server in the name")
    void antigravityNameCandidatesAreStamped(String name, String server, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.event(name, EMPTY));
        Attestations.Stamp stamp = fixture.stampCarrier(server, AGY.author(HookFixture.HOST_ID), "save", EMPTY,
                AGY.stampedPart(run));
        assertThat(run.document()).isEqualTo(AGY.allowed(EMPTY, stamp.token(), stamp.addedRequestId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcp:xledger_save", "mcp:test:ledger_get", "ledger_save", "mcp:ledger_save",
            "mcp:test:other_ledger_save", "mcp:a b:ledger_save", "mcp::ledger_save"})
    @DisplayName("HK-011 Antigravity names that are no ledger write tool, name no server, or have a prefix other than the server are not touched")
    void antigravityOtherNamesAreNotTouched(String name, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.event(name, EMPTY));
        assertThat(run.document()).isEqualTo(AGY.untouched());
    }

    private static Stream<String> writeOps() {
        return Hosts.WRITE_OPS.stream();
    }

    /** The gateway form of Codex: the tool name carries the name of its upstream, which is the server segment. */
    private static String codexGateway(String op) {
        return "mcp__test__test_ledger_" + op;
    }

    /** The Codex Apps namespace (a real path): the audience is the app, so the server identity is codex_apps.<app>. */
    private static String codexAppsNamespace(String op) {
        return "mcp__codex_apps__app_ledger_ledger_" + op;
    }

    private static final String CODEX_APP_SERVER = "codex_apps.ledger";

    @ParameterizedTest
    @MethodSource("writeOps")
    @DisplayName("HK-013 Codex gateway name mcp__test__test_ledger_<op> (prefix = server segment): all ten are stamped")
    void codexGatewayIsStamped(String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "namespace"));
        Run run = fixture.runClean(CODEX, CODEX.event(codexGateway(op), input));
        Attestations.Stamp stamp = fixture.stamp(CODEX, op, input, run);
        assertThat(run.document()).isEqualTo(CODEX.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    @ParameterizedTest
    @MethodSource("writeOps")
    @DisplayName("HK-013 Codex gateway name: a reserved key is denied for all ten")
    void codexGatewayDeniesReservedKey(String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of(Json.RESERVED_KEY, Json.of("null")));
        Run run = fixture.runClean(CODEX, CODEX.event(codexGateway(op), input));
        assertThat(run.document()).isEqualTo(CODEX.denied(Refusal.RESERVED_FIELD));
    }

    @ParameterizedTest
    @MethodSource("writeOps")
    @DisplayName("HK-013 Codex Apps namespace mcp__codex_apps__app_ledger_ledger_<op>: all ten are stamped, with the key of the server identity codex_apps.ledger")
    void codexAppsNamespaceIsStamped(String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "codex apps"));
        Run run = fixture.runClean(CODEX, CODEX.event(codexAppsNamespace(op), input));
        Attestations.Stamp stamp = fixture.stampCarrier(CODEX_APP_SERVER, CODEX.author(ID), op, input,
                CODEX.stampedPart(run));
        assertThat(stamp.claims().tool()).isEqualTo("ledger_" + op);
        assertThat(run.document()).isEqualTo(CODEX.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    @ParameterizedTest
    @MethodSource("writeOps")
    @DisplayName("HK-013 Codex Apps namespace: a reserved key is denied for all ten")
    void codexAppsNamespaceDeniesReservedKey(String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of(Json.RESERVED_KEY, Json.of("null")));
        Run run = fixture.runClean(CODEX, CODEX.event(codexAppsNamespace(op), input));
        assertThat(run.document()).isEqualTo(CODEX.denied(Refusal.RESERVED_FIELD));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcp__codex_apps__app_ledger_ledger_get", "mcp__codex_apps__app_ledger_ledger_history",
            "mcp__codex_apps__app_ledger_ledger_save_extra", "mcp__codex_apps__app_ledger_save",
            "mcp__codex_apps__app_ledger_ledger_Save", "mcp__codex_apps__app__ledger_save",
            "mcp__other__app_ledger_ledger_save", "mcp__codex_apps__ledger_ledger_get", "mcp__codex_apps__apps_ledger_ledger_save"})
    @DisplayName("HK-013 Codex Apps namespace: a non-ledger or read operation, a malformed app segment, or another pseudo server is not touched")
    void codexAppsNamespaceOtherNamesAreUntouched(String name, HookFixture fixture) {
        Run run = fixture.runClean(CODEX, CODEX.event(name, EMPTY));
        assertThat(run.document()).isEqualTo(CODEX.untouched());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "history", "search", "defects"})
    @DisplayName("HK-013 Codex gateway name: read tool names are not handled")
    void codexGatewayLeavesReadToolsAlone(String op, HookFixture fixture) {
        Run run = fixture.runClean(CODEX, CODEX.event(codexGateway(op), EMPTY));
        assertThat(run.document()).isEqualTo(CODEX.untouched());
    }

    // ---- HK-012: call_mcp_tool envelope ----

    record Keep(boolean keep) {}

    record Deep(List<Object> keep) {}

    record WrapperInput(String marker, String author_id, Deep nested) {}

    private static final JsonNode INPUT = Json.tree(new WrapperInput(
            "wrapper", "model:declared", new Deep(java.util.Arrays.asList(1, null, false))));

    private static Envelope envelope(String toolName, Object arguments) {
        return new Envelope("t0001sink", toolName, arguments, new Keep(true));
    }

    @ParameterizedTest
    @MethodSource("writeOps")
    @DisplayName("HK-012 call_mcp_tool with Arguments as an object: only Arguments is overwritten, still an object, envelope untouched")
    void wrapperObjectArguments(String op, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope("t0001sink_ledger_" + op, INPUT)));
        Attestations.Stamp stamp = fixture.stampCarrier("t0001sink", AGY.author(ID), op, INPUT, AGY.echoed(run));
        assertThat(run.document()).isEqualTo(AGY.allowedWrapped(Json.stamped(INPUT, stamp.token(), stamp.addedRequestId())));
    }

    @ParameterizedTest
    @MethodSource("writeOps")
    @DisplayName("HK-012 call_mcp_tool with Arguments as JSON text: only Arguments is overwritten, still JSON text, envelope untouched")
    void wrapperTextArguments(String op, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope("t0001sink_ledger_" + op, Json.MAPPER.writeValueAsString(INPUT))));
        JsonNode text = AGY.echoed(run);
        assertThat(text.isString()).as("Arguments stays JSON text").isTrue();
        Attestations.Stamp stamp = fixture.stampCarrier("t0001sink", AGY.author(ID), op, INPUT, Json.of(text.stringValue()));
        assertThat(run.document()).isEqualTo(AGY.allowedWrapped(
                Json.MAPPER.writeValueAsString(Json.stamped(INPUT, stamp.token(), stamp.addedRequestId()))));
    }

    private static final List<JsonNode> RESERVED_VALUES = List.of(
            Json.of("null"), Json.text(""), Json.of("false"), Json.of("{}"), Json.of("[]"), Json.text("gemini:" + ID));

    private static Stream<JsonNode> reservedValues() {
        return RESERVED_VALUES.stream();
    }

    @ParameterizedTest
    @MethodSource("reservedValues")
    @DisplayName("HK-012 call_mcp_tool: a reserved key in Arguments given as an object is denied")
    void wrapperReservedKeyInObject(JsonNode value, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope("ledger_save", Map.of(Json.RESERVED_KEY, value))));
        assertThat(run.document()).isEqualTo(AGY.denied(Refusal.RESERVED_FIELD));
    }

    @ParameterizedTest
    @MethodSource("reservedValues")
    @DisplayName("HK-012 call_mcp_tool: a reserved key in Arguments given as JSON text is denied")
    void wrapperReservedKeyInText(JsonNode value, HookFixture fixture) {
        String text = Json.MAPPER.writeValueAsString(Map.of(Json.RESERVED_KEY, value));
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope("ledger_save", text)));
        assertThat(run.document()).isEqualTo(AGY.denied(Refusal.RESERVED_FIELD));
    }

    @Test
    @DisplayName("HK-012 call_mcp_tool: a reserved key on the envelope itself is denied")
    void wrapperReservedKeyOnEnvelope(HookFixture fixture) {
        Map<String, Object> envelope = Map.of(
                "ServerName", "s", "ToolName", "ledger_save", "Arguments", EMPTY, Json.RESERVED_KEY, 1);
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope));
        assertThat(run.document()).isEqualTo(AGY.denied(Refusal.RESERVED_FIELD));
    }

    /** Invalid Arguments with the reason the hook must give: not an object, or not JSON text of an object. */
    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.argumentSet("null", Json.of("null"), Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("[]", Json.of("[]"), Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("7", Json.of("7"), Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("missing", null, Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("text {", "{", Refusal.ARGUMENTS_NOT_JSON_OBJECT),
                Arguments.argumentSet("text {} {}", "{} {}", Refusal.ARGUMENTS_NOT_JSON_OBJECT),
                Arguments.argumentSet("text \"text\"", "\"text\"", Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("text []", "[]", Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("text null", "null", Refusal.ARGUMENTS_NOT_OBJECT),
                Arguments.argumentSet("text empty", "", Refusal.ARGUMENTS_NOT_OBJECT));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    @DisplayName("HK-012 call_mcp_tool: invalid Arguments (null, [], 7, missing, unparsable or non-object text) are denied")
    void wrapperInvalidArguments(Object arguments, Refusal reason, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope("ledger_save", arguments)));
        assertThat(run.document()).isEqualTo(AGY.denied(reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledger_get", "unrelated_save", "ledger_save_extra", "ledger_history"})
    @DisplayName("HK-012 call_mcp_tool: a ToolName that is no ledger write tool passes, even with a reserved key")
    void wrapperOtherToolPasses(String toolName, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope(toolName, Map.of(Json.RESERVED_KEY, Json.of("null")))));
        assertThat(run.document()).isEqualTo(AGY.untouched());
    }

    /** Envelopes that are not a ledger call: ServerName missing, empty or not text, ToolName not text, not an object. */
    static Stream<Arguments> brokenEnvelopes() {
        return Stream.of(
                Arguments.argumentSet("no ServerName", new Envelope(null, "ledger_save", EMPTY, null)),
                Arguments.argumentSet("empty ServerName", new Envelope("", "ledger_save", EMPTY, null)),
                Arguments.argumentSet("numeric ServerName", new Envelope(3, "ledger_save", EMPTY, null)),
                Arguments.argumentSet("numeric ToolName", new Envelope("s", 7, EMPTY, null)),
                Arguments.argumentSet("array envelope", Json.of("[]")),
                Arguments.argumentSet("null envelope", Json.of("null")),
                Arguments.argumentSet("text envelope", Json.text("text")));
    }

    @ParameterizedTest
    @MethodSource("brokenEnvelopes")
    @DisplayName("HK-012 call_mcp_tool: a missing or empty ServerName, a non-text name or a non-object envelope passes")
    void wrapperBrokenEnvelopePasses(Object envelope, HookFixture fixture) {
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope));
        assertThat(run.document()).isEqualTo(AGY.untouched());
    }

    record Unknown(List<Object> a) {}

    record UnknownFields(Unknown unknown, String note) {}

    @Test
    @DisplayName("HK-012 call_mcp_tool keeps unknown fields and Korean text of Arguments")
    void wrapperUnknownFieldsSurvive(HookFixture fixture) {
        JsonNode input = Json.tree(new UnknownFields(new Unknown(List.of(1, 2, Map.of("b", Json.of("null")))), "한글"));
        Run run = fixture.runClean(AGY, AGY.wrapperEvent(envelope("ledger_batch", input)));
        Attestations.Stamp stamp = fixture.stampCarrier("t0001sink", AGY.author(ID), "batch", input, AGY.echoed(run));
        assertThat(run.document()).isEqualTo(AGY.allowedWrapped(Json.stamped(input, stamp.token(), stamp.addedRequestId())));
    }
}
