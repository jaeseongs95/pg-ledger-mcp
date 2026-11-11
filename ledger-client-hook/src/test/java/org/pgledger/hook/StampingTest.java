package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** HK-001..HK-006, HK-009, HK-020: the stamping rules (a token signed with the author's key), identical for the three clients. */
@ExtendWith(HookExtension.class)
class StampingTest {

    record Sentinel(boolean sentinel) {}

    record Nested(List<Object> items) {}

    /** Nested data, Korean text, a {@code __proto__} key and a legacy {@code author_id}. */
    record ModelInput(String project, Nested nested, String note, Sentinel __proto__, String author_id) {}

    private static final JsonNode NESTED_INPUT = Json.tree(new ModelInput(
            "TEST", new Nested(Arrays.asList(1, null, false)), "한글", new Sentinel(true), "other:declared"));

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#withWriteOps")
    @DisplayName("HK-001 each write tool: _hostAttestation is a token signed by the author's key (author <vendor>:<host id>, tool ledger_<op>, request id, fixed issue time), every other field unchanged, exactly the host's answer shape")
    void stampsWriteTool(HostScenario host, String op, HookFixture fixture) {
        Run run = fixture.runClean(host, host.event(host.toolName(op), NESTED_INPUT));
        Attestations.Stamp stamp = fixture.stamp(host, op, NESTED_INPUT, run);
        assertThat(stamp.claims().author()).isEqualTo(host.author(HookFixture.HOST_ID));
        assertThat(stamp.claims().tool()).isEqualTo("ledger_" + op);
        assertThat(stamp.claims().issuedAt()).isEqualTo(HookFixture.NOW.getEpochSecond());
        assertThat(run.document()).isEqualTo(host.allowed(NESTED_INPUT, stamp.token(), stamp.addedRequestId()));
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-002 an absent author_id is not created")
    void doesNotCreateAuthorId(HostScenario host, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("project", "TEST"));
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "whoami", input, run);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#withWriteOps")
    @DisplayName("HK-003 an existing author_id (cached connector schema) is kept and never copied into _hostAttestation")
    void keepsExistingAuthorId(HostScenario host, String op, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("author_id", "other:declared", "marker", "cached-schema"));
        Run run = fixture.runClean(host, host.event(host.toolName(op), input));
        Attestations.Stamp stamp = fixture.stamp(host, op, input, run);
        assertThat(stamp.claims().author()).isEqualTo(host.author(HookFixture.HOST_ID));
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    /** A text in the form of a token (six parts, the format prefix); it is a forgery attempt like any other value. */
    private static final String TOKEN_LOOKALIKE =
            "pgla2.Y2xhdWRlOnBhcmVudA.bGVkZ2VyX3NhdmU.cmVxLTE.1794387600.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** Reserved values: every JSON type, the value the hook would write itself, a token-like text and another id. */
    static Stream<Arguments> reservedValues() {
        return Hosts.ALL.stream().flatMap(host -> Stream.of(
                        Json.of("null"), Json.text(""), Json.of("false"), Json.of("0"), Json.of("{}"), Json.of("[]"),
                        Json.text(host.author(HookFixture.HOST_ID)), Json.text("other:forged"), Json.text(TOKEN_LOOKALIKE))
                .flatMap(value -> Hosts.WRITE_OPS.stream()
                        .map(op -> Arguments.argumentSet(host + " " + op + " " + value, host, op, value))));
    }

    @ParameterizedTest
    @MethodSource("reservedValues")
    @DisplayName("HK-004 a model-supplied _hostAttestation of any value (token look-alikes included) is denied as reserved, nothing is rewritten or signed, no key is created")
    void deniesReservedKey(HostScenario host, String op, JsonNode value, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of(Json.RESERVED_KEY, value));
        Run run = fixture.runClean(host, host.event(host.toolName(op), input));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.RESERVED_FIELD));
        assertThat(run.out()).contains("reserved").doesNotContain("updatedInput").doesNotContain("overwrite")
                .doesNotContain("pgla2");
        assertThat(fixture.keyDirectory()).as("no key folder, so no key was created").doesNotExist();
    }

    static final List<JsonNode> INVALID_IDS = List.of(
            Json.of("null"), Json.text(""), Json.of("3"), Json.of("{}"), Json.of("[]"), Json.of("true"),
            Json.text("x".repeat(129)), Json.text("white space"), Json.text(" "), Json.text("other:id"), Json.text("bad:id"),
            Json.text("x\n"), Json.text("x\r\n"), Json.text("x\r"), Json.text("한글"), Json.text("a/b"));

    static Stream<Arguments> invalidIds() {
        return Hosts.cross(INVALID_IDS);
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    @DisplayName("HK-005 an invalid host id is denied, with no fallback to an agent id or author_id")
    void deniesInvalidHostId(HostScenario host, JsonNode hostId, HookFixture fixture) {
        JsonNode input = Json.tree(Map.of("author_id", host.author("fallback")));
        Run run = fixture.runClean(host, host.event(host.toolName("save"), input, hostId));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.HOST_ID_INVALID));
        assertThat(run.out()).doesNotContain("fallback").doesNotContain("child-");
    }

    @ParameterizedTest
    @MethodSource("org.pgledger.hook.Hosts#all")
    @DisplayName("HK-005 a missing host id is denied, with no fallback to an agent id")
    void deniesMissingHostId(HostScenario host, HookFixture fixture) {
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), Json.of("{}"), null));
        assertThat(run.document()).isEqualTo(host.denied(Refusal.HOST_ID_INVALID));
        assertThat(run.out()).doesNotContain("child-");
    }

    static Stream<Arguments> validIds() {
        return Hosts.cross(List.of("x", "a._-Z9", "x".repeat(128)));
    }

    @ParameterizedTest
    @MethodSource("validIds")
    @DisplayName("HK-006 ids of 1 char, the full character set and 128 chars are used as they are")
    void acceptsValidIds(HostScenario host, String id, HookFixture fixture) {
        JsonNode input = Json.of("{}");
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), input, Json.text(id)));
        Attestations.Stamp stamp = fixture.stamp(host, id, "whoami", input, run);
        assertThat(stamp.claims().author()).isEqualTo(host.author(id));
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    /** Two ids one after the other, the second equal to the first or not. */
    static Stream<Arguments> idSequences() {
        return Hosts.ALL.stream().flatMap(host -> Stream.of(
                Arguments.argumentSet(host + " A then B", host, "parent-A", "parent-B"),
                Arguments.argumentSet(host + " B then A", host, "parent-B", "parent-A"),
                Arguments.argumentSet(host + " A then A", host, "parent-A", "parent-A")));
    }

    @ParameterizedTest
    @MethodSource("idSequences")
    @DisplayName("HK-009 each invocation uses the id it is given (A, B, A), no state between runs")
    void keepsNoStateBetweenRuns(HostScenario host, String first, String second, HookFixture fixture) {
        JsonNode input = Json.of("{}");
        fixture.runClean(host, host.event(host.toolName("save"), input, Json.text(first)));
        Run run = fixture.runClean(host, host.event(host.toolName("save"), input, Json.text(second)));
        Attestations.Stamp stamp = fixture.stamp(host, second, "save", input, run);
        assertThat(stamp.claims().author()).isEqualTo(host.author(second));
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

    static Stream<Arguments> vendorPrefixes() {
        return Stream.of(
                Arguments.of(Hosts.ALL.get(0), "claude"),
                Arguments.of(Hosts.ALL.get(1), "codex"),
                Arguments.of(Hosts.ALL.get(2), "gemini"));
    }

    @ParameterizedTest
    @MethodSource("vendorPrefixes")
    @DisplayName("HK-020 the vendor prefix of the signed author follows the client enum (claude:, codex:, gemini:)")
    void vendorPrefixFollowsClient(HostScenario host, String prefix, HookFixture fixture) {
        JsonNode input = Json.of("{}");
        Run run = fixture.runClean(host, host.event(host.toolName("whoami"), input));
        Attestations.Stamp stamp = fixture.stamp(host, "whoami", input, run);
        assertThat(stamp.claims().author()).isEqualTo(prefix + ":" + HookFixture.HOST_ID);
        assertThat(run.document()).isEqualTo(host.allowed(input, stamp.token(), stamp.addedRequestId()));
    }

}
