package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.write.WriteStatus;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.BatchResults;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.ReadCase;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.support.WriteCase;
import org.pgledger.mcp.tool.WriteOutputs;
import org.pgledger.mcp.tool.WriteOutputs.Grant;
import org.pgledger.mcp.tool.WriteOutputs.Whoami;

/** MCW cases about calling the write tools: attestation, arguments, forms, results, errors, channels. */
@McpServerTest
class WriteCallTest {

    private static final String UNKNOWN_ARGUMENT = "is not a known argument";

    // ------------------------------------------------------------------------------------- MCW-003 .. MCW-006

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-003: every write tool refuses a call without _hostAttestation and writes nothing")
    void noWriteWithoutAttestation(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        ToolError error = session.failed(write.tool(), write.callWithout(seed));

        assertThat(error.code()).isEqualTo("HOST_ATTESTATION_REQUIRED");
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @Test
    @DisplayName("MCW-004: whoami accepts the legacy author_id and ignores it: the author is the attestation")
    void whoamiIgnoresTheLegacyAuthorId(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("whoami", "defect", "task", "fix", "verify");

        Whoami who = session.ok("ledger_whoami", new Calls.Whoami(author, Scenario.NOGRANT), Whoami.class);

        assertThat(who).isEqualTo(new Whoami("ledger_author", author, true, false,
                List.of(new Grant(scenario.projectId(), "defect", null), new Grant(scenario.projectId(), "fix", null),
                        new Grant(scenario.projectId(), "task", null),
                        new Grant(scenario.projectId(), "verify", null))));
    }

    @Test
    @DisplayName("MCW-005: save with only the legacy author_id is HOST_ATTESTATION_REQUIRED")
    void theLegacyAuthorIdAloneAuthorisesNothing(McpSession session, Scenario scenario) {
        ToolError error = session.failed("ledger_save",
                Calls.Save.create("defect", Fields.defect(scenario.projectId(), "t"), null)
                        .withAuthorId(Scenario.AUTHOR));

        assertThat(error.code()).isEqualTo("HOST_ATTESTATION_REQUIRED");
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    /** An unknown argument added to a valid save: where it is and the path that must be named. */
    private record UnknownArgument(String path, UnaryOperator<Args> add) {

        @Override
        public String toString() {
            return path;
        }
    }

    static Stream<UnknownArgument> unknownArguments() {
        return Stream.of(
                new UnknownArgument("titel", call -> call.with("titel", "x")),
                new UnknownArgument("authorId", call -> call.with("authorId", Scenario.AUTHOR)),
                new UnknownArgument("Author_Id", call -> call.with("Author_Id", Scenario.AUTHOR)),
                new UnknownArgument("_hostattestation", call -> call.with("_hostattestation", Scenario.AUTHOR)),
                new UnknownArgument("fields.titel",
                        call -> call.with("fields", Args.of().with("title", "t").with("titel", "x"))),
                new UnknownArgument("fields.mentioned",
                        call -> call.with("fields", Args.of().with("title", "t").with("mentioned", List.of("title")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownArguments")
    @DisplayName("MCW-006: an unknown argument is VALIDATION_FAILED and names the argument")
    void anUnknownArgumentIsNamed(UnknownArgument unknown, McpSession session, Scenario scenario) {
        Args call = unknown.add().apply(Args.from(session.mapper(),
                Calls.Save.create("defect", Fields.defect(scenario.projectId(), "t"), Scenario.AUTHOR)));

        ToolError error = session.failed("ledger_save", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue(unknown.path(), UNKNOWN_ARGUMENT, List.of()));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    @DisplayName("MCW-006: all unknown arguments of one call are reported together")
    void allUnknownArgumentsAreReportedTogether(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(),
                Calls.Save.create("defect", Fields.defect(scenario.projectId(), "t"), Scenario.AUTHOR))
                .with("titel", "x").with("authorId", Scenario.AUTHOR).with("Author_Id", Scenario.AUTHOR)
                .with("_hostattestation", Scenario.AUTHOR)
                .with("fields", Args.of().with("title", "t").with("titel", "x"));

        ToolError error = session.failed("ledger_save", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactlyInAnyOrder("titel", "authorId",
                "Author_Id", "_hostattestation", "fields.titel");
    }

    /** A batch operation that is wrong in its {@code op} key, and what the issue says about it. */
    private record BadOp(String name, Map<String, Object> op, String reason, List<String> allowed) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<BadOp> badOps() {
        List<String> ops = List.of("save", "transition", "record_fix", "record_case", "link", "correct");
        return Stream.of(
                new BadOp("unknown op", Map.of("op", "drop"), "unknown value drop", ops),
                new BadOp("no op", Map.of("entity", "defect"), "is required", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badOps")
    @DisplayName("MCW-027: a batch operation with an unknown or missing op names the key and the allowed ops, and "
            + "no internal class name is in the result")
    void aBadOpNamesNoInternalClass(BadOp bad, McpSession session) {
        Args call = Args.of().with("ops", List.of(bad.op())).with("_hostAttestation", Scenario.AUTHOR);

        CallToolResult result = session.call("ledger_batch", call);
        ToolError error = session.error(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("ops[0].op", bad.reason(), bad.allowed()));
        String everything = result.content() + " " + result.structuredContent();
        assertThat(everything).doesNotContain("org.pgledger").doesNotContain("BatchOp");
    }

    @Test
    @DisplayName("MCW-006: an empty _hostAttestation is AUTHOR_ID_FORMAT")
    void anEmptyAttestationIsAMalformedAuthorId(McpSession session, Scenario scenario) {
        ToolError error = session.failed("ledger_save",
                Calls.Save.create("defect", Fields.defect(scenario.projectId(), "t"), ""));

        assertThat(error.code()).isEqualTo("AUTHOR_ID_FORMAT");
    }

    // ------------------------------------------------------------------------------------- MCW-007 .. MCW-009

    @Test
    @DisplayName("MCW-007: fields as an object and as the legacy JSON text with the same request id are one write "
            + "and a receipt replay")
    void fieldsAsObjectAndAsTextAreTheSameRequest(McpSession session, Scenario scenario) {
        String request = UUID.randomUUID().toString();
        Fields fields = Fields.defect(scenario.projectId(), "legacy form");

        WriteOutputs.Saved first = session.ok("ledger_save",
                Calls.Save.create("defect", fields, Scenario.AUTHOR).withRequestId(request), WriteOutputs.Saved.class);
        WriteOutputs.Saved replay = session.ok("ledger_save", new Calls.LegacySave("defect",
                session.mapper().writeValueAsString(fields), Scenario.REASON, request, Scenario.AUTHOR),
                WriteOutputs.Saved.class);

        assertThat(replay).isEqualTo(first);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-008: ops as an array and as the legacy JSON text give identical results")
    void opsAsArrayAndAsTextGiveIdenticalResults(McpSession session, Scenario scenario) {
        String request = UUID.randomUUID().toString();
        List<Calls.Op> ops = List.of(Calls.Op.save("defect", Fields.defect(scenario.projectId(), "batched")));

        BatchResults first = session.ok("ledger_batch",
                new Calls.Batch(ops, request, Scenario.AUTHOR), BatchResults.class);
        BatchResults replay = session.ok("ledger_batch", new Calls.LegacyBatch(
                session.mapper().writeValueAsString(ops), request, Scenario.AUTHOR), BatchResults.class);

        assertThat(replay).isEqualTo(first);
        assertThat(first.count()).isEqualTo(1);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    /** A call with one argument of a form the tool does not accept. */
    private record Malformed(String name, String tool, Args rest, String key, Object value) {

        @Override
        public String toString() {
            return name;
        }

        Args call() {
            return rest.with(key, value);
        }
    }

    private static Malformed badFields(String name, Object value) {
        return new Malformed("fields " + name, "ledger_save", Args.of().with("_hostAttestation", Scenario.AUTHOR)
                .with("entity", "defect").with("reason", Scenario.REASON), "fields", value);
    }

    private static Malformed badOps(String name, Object value) {
        return new Malformed("ops " + name, "ledger_batch", Args.of().with("_hostAttestation", Scenario.AUTHOR),
                "ops", value);
    }

    static Stream<Malformed> malformedStructures() {
        return Stream.of(
                badFields("is a text that is not JSON", "{"),
                badFields("is a JSON array text", "[]"),
                badFields("is a JSON number text", "7"),
                badFields("is the text null", "null"),
                badFields("is null", null),
                badFields("is an empty object", Args.of()),
                badOps("is a text that is not JSON", "{"),
                badOps("is an object", Args.of()),
                badOps("is an empty array", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedStructures")
    @DisplayName("MCW-009: a structured argument of the wrong form is VALIDATION_FAILED and nothing is written")
    void aMalformedStructureIsRefused(Malformed malformed, McpSession session, Scenario scenario) {
        ToolError error = session.failed(malformed.tool(), malformed.call());

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    // ------------------------------------------------------------------------------------- MCW-010 .. MCW-013

    @Test
    @DisplayName("MCW-010: whoami of a granted attestation: session_user, author, author_format_ok, can_verify_login, "
            + "grants")
    void whoamiOfAGrantedAuthor(McpSession session, Scenario scenario) {
        String author = scenario.actorWith("defect-only", "defect");

        Whoami who = session.ok("ledger_whoami", Calls.Whoami.as(author), Whoami.class);

        assertThat(who).isEqualTo(new Whoami("ledger_author", author, true, false,
                List.of(new Grant(scenario.projectId(), "defect", null))));
    }

    @Test
    @DisplayName("MCW-011: whoami of an author without grants has none")
    void whoamiOfAnUngrantedAuthor(McpSession session) {
        Whoami who = session.ok("ledger_whoami", Calls.Whoami.as(Scenario.NOGRANT), Whoami.class);

        assertThat(who).isEqualTo(new Whoami("ledger_author", Scenario.NOGRANT, true, false, List.of()));
    }

    @Test
    @DisplayName("MCW-011: whoami of a malformed attestation says the format is wrong")
    void whoamiOfAMalformedAttestation(McpSession session) {
        Whoami who = session.ok("ledger_whoami", Calls.Whoami.as("claude"), Whoami.class);

        assertThat(who).isEqualTo(new Whoami("ledger_author", "claude", false, false, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-012: the result of a write tool is the result record itself: structured content and text agree, "
            + "no wrapper, no error")
    void theResultIsTheRecordItself(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        CallToolResult result = session.call(write.tool(), write.call(seed));

        assertThat(result.isError()).isFalse();
        assertThat(((Map<?, ?>) result.structuredContent()).containsKey("rows")).isFalse();
        assertThat(((Map<?, ?>) result.structuredContent()).containsKey("result")).isFalse();
        Object structured = session.read(result, write.output());
        Object text = session.mapper().readValue(McpSession.text(result), write.output());
        assertThat(structured).isEqualTo(text);
    }

    /** A call that fails with the token. */
    private record Failure(String name, String tool, String token, Function<Seed, Object> call) {

        @Override
        public String toString() {
            return token + " " + name;
        }
    }

    static Stream<Failure> failures() {
        return Stream.of(
                new Failure("no attestation", "ledger_save", "HOST_ATTESTATION_REQUIRED",
                        seed -> Calls.Save.create("defect", Fields.defect(seed.project(), "t"), null)),
                new Failure("unknown argument", "ledger_whoami", "VALIDATION_FAILED",
                        seed -> Args.of().with("bogus", 1)),
                new Failure("no grant", "ledger_save", "SESSION_NOT_GRANTED",
                        seed -> Calls.Save.create("defect", Fields.defect(seed.project(), "t"), Scenario.NOGRANT)),
                new Failure("old version", "ledger_save", "VERSION_CONFLICT",
                        seed -> Calls.Save.edit("defect", seed.defect(), 9, Fields.titled("new"), Scenario.AUTHOR)),
                new Failure("unknown id", "ledger_transition", "NOT_FOUND",
                        seed -> Calls.Transition.of("defect", seed.defect().replace("0001", "0099"), 1, "DISPUTED",
                                Scenario.AUTHOR).withEvidence(Scenario.EVIDENCE)),
                new Failure("unknown category", "ledger_save", "CODE_INVALID", seed -> Calls.Save.create("defect",
                        new Fields(seed.project(), "t", "ZZZ", "P2", "b", "c", null, null, null, null, null, null,
                                null, null, null), Scenario.AUTHOR)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    @DisplayName("MCW-013: a failed call is isError with the token as code and as the start of the text, in English, "
            + "without SQL, class names or stack traces")
    void aFailureIsTheErrorOfTheResult(Failure failure, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        CallToolResult result = session.call(failure.tool(), failure.call().apply(seed));
        ToolError error = session.error(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.code()).isEqualTo(failure.token());
        assertThat(error.message()).isNotBlank();
        assertThat(McpSession.text(result)).isEqualTo(failure.token() + ": " + error.message());
        assertThat(McpSession.text(result)).doesNotContainIgnoringCase("select ").doesNotContainIgnoringCase("sql")
                .doesNotContain("Exception").doesNotContain("org.").doesNotContain("java.").doesNotContain("\tat ")
                .doesNotContain("jdbc");
    }

    // ------------------------------------------------------------------------------------- MCW-015 .. MCW-019

    @Test
    @DisplayName("MCW-015: ledger_review without evidence_uri is VALIDATION_FAILED at evidence_uri")
    void reviewNeedsEvidence(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        ToolError error = session.failed("ledger_review",
                Calls.Review.of("defect", seed.fixed(), 2, "VERIFIED", Scenario.REVIEWER).withoutEvidence());

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("evidence_uri", "is required", List.of()));
    }

    /** A save made invalid in one argument: how, and what the answer must be. */
    private record BadValue(String name, UnaryOperator<Args> change, String token, List<String> paths) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<BadValue> badValues() {
        return Stream.of(
                new BadValue("expected_version as a text", call -> call.with("expected_version", "3"),
                        "VALIDATION_FAILED", List.of("expected_version")),
                new BadValue("expected_version with a fraction", call -> call.with("expected_version", 3.5),
                        "VALIDATION_FAILED", List.of("expected_version")),
                new BadValue("expected_version negative", call -> call.with("expected_version", -1),
                        "VALIDATION_FAILED", List.of("expected_version")),
                new BadValue("request_id that is no UUID", call -> call.with("request_id", "not-a-uuid"),
                        "VALIDATION_FAILED", List.of("request_id")),
                new BadValue("reason of nine characters", call -> call.with("reason", "123456789"),
                        "REASON_INVALID", List.of("reason")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badValues")
    @DisplayName("MCW-018: a text, a fraction, a negative version, a request id that is no UUID and a short reason are "
            + "refused without coercion")
    void aBadValueIsRefused(BadValue bad, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();
        Args call = Args.from(session.mapper(),
                Calls.Save.edit("defect", seed.defect(), 1, Fields.titled("edited"), Scenario.AUTHOR));

        ToolError error = session.failed("ledger_save", bad.change().apply(call));

        assertThat(error.code()).isEqualTo(bad.token());
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactlyElementsOf(bad.paths());
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @Test
    @DisplayName("MCW-019: a verification through ledger_transition is refused by the schema")
    void transitionCannotVerify(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        ToolError error = session.failed("ledger_transition",
                Calls.Transition.of("defect", seed.fixed(), 2, "VERIFIED", Scenario.REVIEWER));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("MCW-019: a verification in a batch (author channel) is VERIFY_NEEDS_REVIEWER_LOGIN")
    void batchCannotVerify(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        ToolError error = session.failed("ledger_batch",
                Calls.Batch.of(Scenario.REVIEWER, Calls.Op.transition("defect", seed.fixed(), 2, "VERIFIED")));

        assertThat(error.code()).isEqualTo("VERIFY_NEEDS_REVIEWER_LOGIN");
        assertThat(Ledger.get(session, seed.fixed()).items()).extracting(item -> item.state())
                .containsExactly("SOURCE_FIXED");
    }

    @Test
    @DisplayName("MCW-019: ledger_review verifies as the reviewer")
    void reviewVerifies(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        WriteOutputs.Transitioned verified = session.ok("ledger_review",
                Calls.Review.of("defect", seed.fixed(), 2, "VERIFIED", Scenario.REVIEWER),
                WriteOutputs.Transitioned.class);

        assertThat(verified).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new WriteOutputs.Transitioned(WriteStatus.COMMITTED, "defect", seed.fixed(),
                        "SOURCE_FIXED", "VERIFIED", 3, 0, Scenario.REVIEWER, "ledger_reviewer"));
    }

    @Test
    @DisplayName("MCW-019: ledger_correct refuses a verification row, ledger_review_correct corrects it")
    void correctionOfAVerificationRow(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        ToolError refused = session.failed("ledger_correct", Calls.Correct.of(seed.verificationRow(), Scenario.AUTHOR));
        WriteOutputs.Corrected corrected = session.ok("ledger_review_correct",
                Calls.Correct.of(seed.verificationRow(), Scenario.REVIEWER), WriteOutputs.Corrected.class);

        assertThat(refused.code()).isEqualTo("VERIFY_NEEDS_REVIEWER_LOGIN");
        assertThat(corrected.corrects()).isEqualTo(seed.verificationRow());
        assertThat(corrected.login()).isEqualTo("ledger_reviewer");
    }

    // ------------------------------------------------------------------------------------------------ MCW-024

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.ReadCase#all")
    @DisplayName("MCW-024: a read tool needs no _hostAttestation and does not declare it")
    void readToolsDoNotTakeTheAttestation(ReadCase read, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        Args call = Args.from(session.mapper(), read.call(seed)).with("_hostAttestation", Scenario.AUTHOR);

        ToolError error = session.failed(read.tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(
                new ToolError.Issue("_hostAttestation", UNKNOWN_ARGUMENT, List.of()));
    }
}
