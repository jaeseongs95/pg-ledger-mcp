package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.hook.AntigravityHost.Envelope;
import tools.jackson.databind.JsonNode;

/** HK-022, HK-023, HK-030: which tool, which request id and which time the signed attestation carries. */
@ExtendWith(HookExtension.class)
class SigningTest {

    /** The naming of ledger_<op> in each host; {@code %s} is the operation. */
    private static final Map<Client, List<String>> NAMINGS = Map.of(
            Client.CLAUDE, List.of("mcp__ledger__ledger_%s", "mcp__srv__ledger_%s", "mcp__codex_apps__app_ledger_ledger_%s"),
            Client.CODEX, List.of("mcp__test__ledger_%s", "mcp__srv__ledger_%s", "mcp__codex_apps__app_ledger_ledger_%s"),
            Client.ANTIGRAVITY, List.of("mcp:ledger_%s", "mcp:test:ledger_%s", "mcp:test/ledger_%s", "mcp:test_ledger_%s"));

    /** (host, the host's tool name, operation) for every naming of every host and all ten write tools. */
    static Stream<Arguments> namings() {
        return Hosts.ALL.stream().flatMap(host -> NAMINGS.get(host.client()).stream().flatMap(template ->
                Hosts.WRITE_OPS.stream().map(op ->
                        Arguments.argumentSet(host + " " + template.formatted(op), host, template.formatted(op), op))));
    }

    @ParameterizedTest
    @MethodSource("namings")
    @DisplayName("HK-022 the tool claim is the canonical ledger_<op> whatever naming the host used (mcp__<server>__ledger_<op>, codex_apps app_ledger_ledger_<op>, mcp:<server>:ledger_<op> ...)")
    void toolClaimIsTheCanonicalName(HostScenario host, String hostToolName, String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("marker", "naming"));
        Run run = fixture.runClean(host, host.event(hostToolName, input));
        Attestations.Stamp stamp = fixture.stamp(host, op, input, run);
        assertThat(stamp.claims().tool()).isEqualTo("ledger_" + op);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    /** The ToolName of an Antigravity {@code call_mcp_tool} envelope: plain and with the connector prefix. */
    private static final List<String> WRAPPER_TOOL_NAMES = List.of("ledger_%s", "app_ledger_ledger_%s", "srv_ledger_%s");

    static Stream<Arguments> wrapperNamings() {
        return WRAPPER_TOOL_NAMES.stream().flatMap(template -> Hosts.WRITE_OPS.stream()
                .map(op -> Arguments.argumentSet(template.formatted(op), template.formatted(op), op)));
    }

    @ParameterizedTest
    @MethodSource("wrapperNamings")
    @DisplayName("HK-022 Antigravity call_mcp_tool: the tool claim is the canonical ledger_<op> for every ToolName form")
    void wrapperToolClaimIsTheCanonicalName(String toolName, String op, HookFixture fixture) {
        AntigravityHost agy = AntigravityHost.INSTANCE;
        JsonNode input = Json.tree(Map.of("marker", "wrapper"));
        Run run = fixture.runClean(agy, agy.wrapperEvent(new Envelope("srv", toolName, input, null)));
        Attestations.Stamp stamp = fixture.stampCarrier(agy.author(HookFixture.HOST_ID), op, input, agy.echoed(run));
        assertThat(stamp.claims().tool()).isEqualTo("ledger_" + op);
    }

    // ---- HK-023: request_id ----

    /** Inputs whose request_id is absent, JSON null or blank: the hook generates one. */
    private static final List<String> WITHOUT_REQUEST_ID = List.of(
            "{\"note\":\"x\"}",
            "{\"note\":\"x\",\"request_id\":null}",
            "{\"note\":\"x\",\"request_id\":\"\"}",
            "{\"note\":\"x\",\"request_id\":\"   \"}",
            "{\"note\":\"x\",\"request_id\":\"\\t\\n\"}");

    static Stream<Arguments> withoutRequestId() {
        return Hosts.cross(WITHOUT_REQUEST_ID);
    }

    @ParameterizedTest
    @MethodSource("withoutRequestId")
    @DisplayName("HK-023 an absent, null or blank request_id is replaced by a generated UUID, the same in the arguments and in the signed token")
    void generatesTheRequestId(HostScenario host, String inputJson, HookFixture fixture) {
        JsonNode input = Json.of(inputJson);
        byte[] event = host.event(host.toolName("save"), input);
        Run run = fixture.runClean(host, event);
        Attestations.Stamp stamp = fixture.stamp(host, "save", input, run);
        assertThat(stamp.addedRequestId()).as("generated request id").isNotNull().isEqualTo(stamp.claims().requestId());
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
        Run again = fixture.runClean(host, event);
        Attestations.Stamp second = fixture.stamp(host, "save", input, again);
        assertThat(second.addedRequestId()).as("a new call gets a new id").isNotEqualTo(stamp.addedRequestId());
    }

    /** Request ids the model chose: kept exactly as they are, spaces, case and non-ASCII included. */
    private static final List<String> OWN_REQUEST_IDS = List.of(
            "abc", " Req 1 ", "UPPER-lower-Mixed", "  leading", "trailing  ", "550E8400-E29B-41D4-A716-446655440000",
            "한글-요청", "0");

    static Stream<Arguments> ownRequestIds() {
        return Hosts.cross(OWN_REQUEST_IDS);
    }

    @ParameterizedTest
    @MethodSource("ownRequestIds")
    @DisplayName("HK-023 a non-blank request_id text is kept unchanged (no trimming, no case change) and signed exactly as it is")
    void keepsTheOwnRequestId(HostScenario host, String requestId, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("note", "x", "request_id", requestId));
        Run run = fixture.runClean(host, host.event(host.toolName("save"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "save", input, run);
        assertThat(stamp.claims().requestId()).isEqualTo(requestId);
        assertThat(stamp.addedRequestId()).as("nothing generated").isNull();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), null));
    }

    /** Request ids of another JSON type: number, boolean, object, array. */
    private static final List<String> INVALID_REQUEST_IDS = List.of(
            "7", "1.5", "0", "true", "false", "{}", "{\"a\":1}", "[]", "[\"x\"]");

    static Stream<Arguments> invalidRequestIds() {
        return Hosts.cross(INVALID_REQUEST_IDS);
    }

    @ParameterizedTest
    @MethodSource("invalidRequestIds")
    @DisplayName("HK-023 a request_id that is a number, boolean, object or array is denied (REQUEST_ID_INVALID): no output arguments, nothing signed, no key created")
    void deniesAnInvalidRequestId(HostScenario host, String requestIdJson, HookFixture fixture) {
        JsonNode input = Json.of("{\"note\":\"x\",\"request_id\":" + requestIdJson + "}");
        Run run = fixture.runClean(host, host.event(host.toolName("save"), input));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.REQUEST_ID_INVALID));
        assertThat(run.out()).doesNotContain("updatedInput").doesNotContain("overwrite").doesNotContain("pgla1");
        assertThat(fixture.keyDirectory()).as("no key folder, so no key was created").doesNotExist();
    }

    static Stream<Arguments> nineWriteOps() {
        return Hosts.cross(Hosts.WRITE_OPS.stream().filter(op -> !"whoami".equals(op)).toList());
    }

    @ParameterizedTest
    @MethodSource("nineWriteOps")
    @DisplayName("HK-023 each of the nine write tools gets a generated request_id when it has none")
    void nineWriteToolsGetARequestId(HostScenario host, String op, HookFixture fixture) {
        JsonNode input = Json.of("{\"note\":\"x\"}");
        Run run = fixture.runClean(host, host.event(host.toolName(op), input));
        Attestations.Stamp stamp = fixture.stamp(host, op, input, run);
        assertThat(stamp.addedRequestId()).isNotNull();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    /** whoami has no request id whatever it carries: the arguments are not touched and never denied for it. */
    private static final List<String> WHOAMI_INPUTS = List.of(
            "{\"note\":\"x\"}", "{\"request_id\":\"keep-me\"}", "{\"request_id\":null}", "{\"request_id\":7}",
            "{\"request_id\":\"\"}");

    static Stream<Arguments> whoamiInputs() {
        return Hosts.cross(WHOAMI_INPUTS);
    }

    @ParameterizedTest
    @MethodSource("whoamiInputs")
    @DisplayName("HK-023 whoami: the request id claim is empty and no request_id is added")
    void whoamiHasNoRequestId(HostScenario host, String inputJson, HookFixture fixture) {
        JsonNode input = Json.of(inputJson);
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "whoami", input, run);
        assertThat(stamp.claims().requestId()).isEmpty();
        assertThat(stamp.addedRequestId()).isNull();
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), null));
    }

    // ---- HK-030: time ----

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-030 issuedAt follows the injected clock: runs 90 s apart give claims 90 s apart, and a fraction of a second is cut off; every token verifies")
    void issuedAtFollowsTheClock(HostScenario host, HookFixture fixture) {
        JsonNode input = Json.of("{\"request_id\":\"r-30\"}");
        byte[] event = host.event(host.toolName("save"), input);
        Attestations.Stamp first = fixture.stamp(host, "save", input, fixture.runClean(host, event));
        fixture.at(HookFixture.NOW.plusSeconds(90));
        Attestations.Stamp second = fixture.stamp(host, "save", input, fixture.runClean(host, event));
        Instant lateInTheSecond = HookFixture.NOW.plusSeconds(90).plusMillis(999);
        fixture.at(lateInTheSecond);
        Attestations.Stamp third = fixture.stamp(host, "save", input, fixture.runClean(host, event));
        assertThat(first.claims().issuedAt()).isEqualTo(HookFixture.NOW.getEpochSecond());
        assertThat(second.claims().issuedAt() - first.claims().issuedAt()).isEqualTo(90L);
        assertThat(third.claims().issuedAt()).isEqualTo(second.claims().issuedAt());
    }
}
