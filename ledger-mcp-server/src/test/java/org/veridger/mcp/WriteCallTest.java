// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.veridger.application.write.WriteStatus;
import org.veridger.mcp.error.ToolError;
import org.veridger.mcp.support.Args;
import org.veridger.mcp.support.BatchResults;
import org.veridger.mcp.support.Calls;
import org.veridger.mcp.support.Calls.Fields;
import org.veridger.mcp.support.Ledger;
import org.veridger.mcp.support.McpServerTest;
import org.veridger.mcp.support.McpSession;
import org.veridger.mcp.support.ReadCase;
import org.veridger.mcp.support.Scenario;
import org.veridger.mcp.support.Seed;
import org.veridger.mcp.support.WriteCase;
import org.veridger.mcp.tool.WriteOutputs;
import org.veridger.mcp.tool.WriteOutputs.Grant;
import org.veridger.mcp.tool.WriteOutputs.Whoami;

/** MCW cases about calling the write tools: attestation, arguments, forms, results, errors, channels. */
@McpServerTest
class WriteCallTest {

    private static final String UNKNOWN_ARGUMENT = "is not a known argument";

    // ------------------------------------------------------------------------------------- MCW-003 .. MCW-006

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.veridger.mcp.support.WriteCase#all")
    @DisplayName("MCW-003: every write tool refuses a call without _hostAttestation and writes nothing")
    void noWriteWithoutAttestation(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        ToolError error = session.failed(write.tool(), write.callWithout(seed));

        assertThat(error.code()).isEqualTo("HOST_ATTESTATION_REQUIRED");
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @Test
    @DisplayName("MCW-004: whoami takes no author_id any more (removed without a shim): it is refused as an unknown "
            + "argument, the author is the attestation and nothing else")
    void whoamiRefusesTheRemovedAuthorId(McpSession session) {
        Args call = Args.from(session.mapper(), Calls.Whoami.as(Scenario.AUTHOR)).with("author_id", Scenario.NOGRANT);

        ToolError error = session.failed("ledger_whoami", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("author_id", UNKNOWN_ARGUMENT, List.of()));
    }

    @Test
    @DisplayName("MCW-005: save with only author_id (no attestation) is VALIDATION_FAILED: author_id is not a known "
            + "argument any more, so it authorises nothing, and nothing is written")
    void theRemovedAuthorIdAloneAuthorisesNothing(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(),
                Calls.IssueSave.create(scenario.projectId(), "t", null))
                .with("author_id", Scenario.AUTHOR);

        ToolError error = session.failed("ledger_issue_save", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("author_id", UNKNOWN_ARGUMENT, List.of()));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    /** An unknown argument added to a valid save: where it is and the path that must be named. */
    private record UnknownArgument(String tool, String path, BiFunction<McpSession, Scenario, Args> call) {

        @Override
        public String toString() {
            return tool + ": " + path;
        }
    }

    static Stream<UnknownArgument> unknownArguments() {
        return Stream.of(
                new UnknownArgument("ledger_issue_save", "titel", (session, scenario) -> Args.from(session.mapper(),
                        Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR)).with("titel", "x")),
                new UnknownArgument("ledger_issue_save", "query", (session, scenario) -> Args.from(session.mapper(),
                        Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR)).with("query", "SELECT 1")),
                new UnknownArgument("ledger_issue_save", "attributes", (session, scenario) -> Args.from(session.mapper(),
                        Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR)).with("attributes", Map.of())),
                new UnknownArgument("ledger_issue_save", "authorId", (session, scenario) -> Args.from(session.mapper(),
                        Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR)).with("authorId", Scenario.AUTHOR)),
                new UnknownArgument("ledger_issue_save", "Author_Id", (session, scenario) -> Args.from(session.mapper(),
                        Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR)).with("Author_Id", Scenario.AUTHOR)),
                new UnknownArgument("ledger_issue_save", "_hostattestation", (session, scenario) -> Args.from(session.mapper(),
                        Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR)).with("_hostattestation", Scenario.AUTHOR)),
                new UnknownArgument("ledger_batch", "ops[0].fields.titel", (session, scenario) -> Args.of()
                        .with("_hostAttestation", Scenario.AUTHOR).with("operationKey", Calls.freshKey())
                        .with("ops", List.of(Args.of().with("op", "save").with("entity", "issue")
                                .with("reason", Scenario.REASON)
                                .with("fields", Args.of().with("title", "t").with("titel", "x"))))),
                new UnknownArgument("ledger_batch", "ops[0].fields.mentioned", (session, scenario) -> Args.of()
                        .with("_hostAttestation", Scenario.AUTHOR).with("operationKey", Calls.freshKey())
                        .with("ops", List.of(Args.of().with("op", "save").with("entity", "issue")
                                .with("reason", Scenario.REASON)
                                .with("fields", Args.of().with("title", "t").with("mentioned", List.of("title")))))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownArguments")
    @DisplayName("MCW-006: an unknown argument is VALIDATION_FAILED and names the argument")
    void anUnknownArgumentIsNamed(UnknownArgument unknown, McpSession session, Scenario scenario) {
        Args call = unknown.call().apply(session, scenario);

        ToolError error = session.failed(unknown.tool(), call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue(unknown.path(), UNKNOWN_ARGUMENT, List.of()));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    void responseFieldsRequireAnArrayBeforeWrite(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(), Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR))
                .with("fields", Map.of("title", "nested value"));

        ToolError error = session.failed("ledger_issue_save", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("fields", "must be of type array", List.of()));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    @DisplayName("MCW-006: all unknown arguments of one call are reported together")
    void allUnknownArgumentsAreReportedTogether(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(),
                Calls.IssueSave.create(scenario.projectId(), "t", Scenario.AUTHOR))
                .with("titel", "x").with("authorId", Scenario.AUTHOR).with("Author_Id", Scenario.AUTHOR)
                .with("_hostattestation", Scenario.AUTHOR).with("query", "SELECT 1");

        ToolError error = session.failed("ledger_issue_save", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactlyInAnyOrder("titel", "authorId",
                "Author_Id", "_hostattestation", "query");
    }

    /** A batch operation that is wrong in its {@code op} key, and what the issue says about it. */
    private record BadOp(String name, Map<String, Object> op, String reason, List<String> allowed) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<BadOp> badOps() {
        List<String> ops = List.of("save", "transition", "record_deliverable", "record_case", "link", "correct",
                "project_version");
        return Stream.of(
                new BadOp("unknown op", Map.of("op", "drop"), "unknown value drop", ops),
                new BadOp("no op", Map.of("entity", "issue"), "is required", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badOps")
    @DisplayName("MCW-027: a batch operation with an unknown or missing op names the key and the allowed ops, and "
            + "no internal class name is in the result")
    void aBadOpNamesNoInternalClass(BadOp bad, McpSession session) {
        Args call = Args.of().with("ops", List.of(bad.op())).with("operationKey", Calls.freshKey())
                .with("_hostAttestation", Scenario.AUTHOR);

        CallToolResult result = session.call("ledger_batch", call);
        ToolError error = session.error(result);

        assertThat(result.isError()).isTrue();
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("ops[0].op", bad.reason(), bad.allowed()));
        String everything = result.content() + " " + result.structuredContent();
        assertThat(everything).doesNotContain("org.veridger").doesNotContain("BatchOp");
    }

    @Test
    @DisplayName("MCW-027: ops that are a text (JSON or not) are refused with the sentence of the reason bundle for "
            + "the wrong type, not the text of the JSON library")
    void textOpsGetTheSentenceOfTheBundle(McpSession session) {
        Args call = Args.of().with("ops", "[{not json").with("operationKey", Calls.freshKey())
                .with("_hostAttestation", Scenario.AUTHOR);

        ToolError error = session.failed("ledger_batch", call);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("ops", "must be of type List", List.of()));
    }

    @Test
    @DisplayName("MCW-006: an empty _hostAttestation is HOST_ATTESTATION_REQUIRED and a malformed author is AUTHOR_ID_FORMAT")
    void anEmptyAttestationIsRequiredAndMalformedIsAuthorIdFormat(McpSession session, Scenario scenario) {
        ToolError empty = session.failed("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "t", ""));
        assertThat(empty.code()).isEqualTo("HOST_ATTESTATION_REQUIRED");

        ToolError malformed = session.failed("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "t", "claude"));
        assertThat(malformed.code()).isEqualTo("AUTHOR_ID_FORMAT");
    }

    // ------------------------------------------------------------------------------------- MCW-007 .. MCW-009

    @Test
    @DisplayName("MCW-007: flat ledger_issue_save is one write and a receipt replay of the same operation key")
    void flatSaveIsReplayed(McpSession session, Scenario scenario) {
        String operationKey = Calls.freshKey();

        WriteOutputs.Saved first = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "flat form", Scenario.AUTHOR)
                        .withOperationKey(operationKey),
                WriteOutputs.Saved.class);
        WriteOutputs.Saved replay = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "flat form", Scenario.AUTHOR)
                        .withOperationKey(operationKey),
                WriteOutputs.Saved.class);

        assertThat(replay).isEqualTo(first);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-008: ops as an array is one batch and a receipt replay of the same operation key; ops as the JSON "
            + "text form is VALIDATION_FAILED (wrong type) and changes nothing")
    void opsAsAnArrayAreReplayedAndAsTextAreRefused(McpSession session, Scenario scenario) {
        String operationKey = Calls.freshKey();
        List<Calls.Op> ops = List.of(Calls.Op.save("issue", Fields.issue(scenario.projectId(), "batched")));

        BatchResults first = session.ok("ledger_batch",
                new Calls.Batch(ops, operationKey, Scenario.AUTHOR), BatchResults.class);
        BatchResults replay = session.ok("ledger_batch",
                new Calls.Batch(ops, operationKey, Scenario.AUTHOR), BatchResults.class);
        ToolError text = session.failed("ledger_batch", new Calls.TextBatch(
                session.mapper().writeValueAsString(ops), operationKey, Scenario.AUTHOR));

        assertThat(replay).isEqualTo(first);
        assertThat(first.count()).isEqualTo(1);
        assertThat(text.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(text.issues()).containsExactly(new ToolError.Issue("ops", "must be of type List", List.of()));
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

    private static Malformed badBatchFields(String name, Object value) {
        return new Malformed("ops[0].fields " + name, "ledger_batch", Args.of()
                .with("_hostAttestation", Scenario.AUTHOR).with("operationKey", Calls.freshKey()),
                "ops", List.of(Args.of().with("op", "save").with("entity", "issue")
                        .with("reason", Scenario.REASON).with("fields", value)));
    }

    private static Malformed badOps(String name, Object value) {
        return new Malformed("ops " + name, "ledger_batch", Args.of().with("_hostAttestation", Scenario.AUTHOR)
                .with("operationKey", Calls.freshKey()), "ops", value);
    }

    static Stream<Malformed> malformedStructures() {
        return Stream.of(
                badBatchFields("is a text that is not JSON", "{"),
                badBatchFields("is a JSON array text", "[]"),
                badBatchFields("is a JSON number text", "7"),
                badBatchFields("is the text null", "null"),
                badBatchFields("is null", null),
                badBatchFields("is an empty object", Args.of()),
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
        String author = scenario.actorWith("issue-only", "issue");

        Whoami who = session.ok("ledger_whoami", Calls.Whoami.as(author), Whoami.class);

        assertThat(who).isEqualTo(new Whoami("ledger_author", author, true, false,
                List.of(new Grant(scenario.projectId(), "issue", null))));
    }

    @Test
    @DisplayName("MCW-010b: whoami of an attestation with live verify grant reports can_verify_login=true and ledger_reviewer")
    void whoamiOfAVerifyGrantedReviewer(McpSession session, Scenario scenario) {
        String reviewer = scenario.actorWith("verify-only", "verify");

        Whoami who = session.ok("ledger_whoami", Calls.Whoami.as(reviewer), Whoami.class);

        assertThat(who).isEqualTo(new Whoami("ledger_reviewer", reviewer, true, true,
                List.of(new Grant(scenario.projectId(), "verify", null))));
        assertThat(session.tool("ledger_whoami").description()).contains("single MCP connection", "can_verify_login");
        assertThat(session.tool("ledger_review").description()).contains("single MCP connection", "verify");
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
    @MethodSource("org.veridger.mcp.support.WriteCase#all")
    @DisplayName("MCW-012: the result of a write tool uses the ApiResult.v1 envelope: structured content and text agree "
            + "and validate against outputSchema")
    void theResultUsesApiResultEnvelope(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        io.modelcontextprotocol.spec.McpSchema.Tool tool = session.tool(write.tool());

        CallToolResult result = session.call(write.tool(), write.call(seed));

        assertThat(result.isError()).isFalse();
        assertThat(io.modelcontextprotocol.json.McpJsonDefaults.getSchemaValidator()
                .validate(tool.outputSchema(), result.structuredContent()).valid()).isTrue();
        Map<?, ?> envelope = (Map<?, ?>) result.structuredContent();
        assertThat(envelope.get("schemaVersion")).isEqualTo("1.0.0");
        assertThat(envelope.get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(envelope.get("error")).isNull();
        Object structured = session.read(result, write.output());
        tools.jackson.databind.JavaType envelopeType = session.mapper().getTypeFactory()
                .constructParametricType(org.veridger.mcp.error.ApiResult.class, write.output());
        org.veridger.mcp.error.ApiResult<?> parsedText = session.mapper().readValue(McpSession.text(result),
                envelopeType);
        assertThat(structured).isEqualTo(parsedText.data());
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
                new Failure("no attestation", "ledger_issue_save", "HOST_ATTESTATION_REQUIRED",
                        seed -> Calls.IssueSave.create(seed.project(), "t", null)),
                new Failure("unknown argument", "ledger_whoami", "VALIDATION_FAILED",
                        seed -> Args.of().with("bogus", 1)),
                new Failure("no grant", "ledger_issue_save", "SESSION_NOT_GRANTED",
                        seed -> Calls.IssueSave.create(seed.project(), "t", Scenario.NOGRANT)),
                new Failure("old version", "ledger_issue_save", "VERSION_CONFLICT",
                        seed -> Calls.IssueSave.editTitle(seed.issue(), 9, "new", Scenario.AUTHOR)),
                new Failure("unknown id", "ledger_transition", "NOT_FOUND",
                        seed -> Calls.Transition.of("issue", seed.issue().replace("0001", "0099"), 1, "DISPUTED",
                                Scenario.AUTHOR).withEvidence(Scenario.EVIDENCE)),
                new Failure("unknown category", "ledger_issue_save", "CODE_INVALID",
                        seed -> Calls.IssueSave.create(seed.project(), "t", Scenario.AUTHOR).withCategory("ZZZ")));
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
                Calls.Review.of("issue", seed.resolved(), 2, "VERIFIED", Scenario.REVIEWER).withoutEvidence());

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).containsExactly(new ToolError.Issue("evidence_uri", "is required", List.of()));
    }

    @Test
    @DisplayName("VRF-007: ledger_record_deliverable and save on VERIFIED issue or spec are VERIFIED_ENTITY, the message "
            + "names the item and says that a reviewer reopens it first, and nothing is written")
    void verifiedEntityRefusesDeliverableAndSave(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofSpec(seed.spec(), "spec-rev-1", Scenario.AUTHOR),
                WriteOutputs.DeliverableRecorded.class);
        session.ok("ledger_review",
                Calls.Review.of("spec", seed.spec(), 2, "VERIFIED", Scenario.REVIEWER),
                WriteOutputs.Transitioned.class);
        int before = Ledger.history(session, seed.project()).size();

        CallToolResult deliverableResult = session.call("ledger_record_deliverable",
                Calls.RecordDeliverable.ofIssue(seed.verified(), "rev-after-verified", Scenario.AUTHOR));
        CallToolResult saveIssueResult = session.call("ledger_issue_save",
                Calls.IssueSave.editTitle(seed.verified(), 3, "changed", Scenario.AUTHOR));
        CallToolResult saveSpecResult = session.call("ledger_spec_save",
                Calls.SpecSave.editTitle(seed.spec(), 3, "changed spec", Scenario.AUTHOR));

        for (CallToolResult result : List.of(deliverableResult, saveIssueResult)) {
            assertThat(result.isError()).isTrue();
            assertThat(McpSession.text(result)).startsWith("VERIFIED_ENTITY: ");
            ToolError error = session.error(result);
            assertThat(error.code()).isEqualTo("VERIFIED_ENTITY");
            assertThat(error.message()).contains(seed.verified(), "reviewer", "ledger_review");
        }
        assertThat(saveSpecResult.isError()).isTrue();
        assertThat(session.error(saveSpecResult).code()).isEqualTo("VERIFIED_ENTITY");
        assertThat(session.error(saveSpecResult).message()).contains(seed.spec(), "reviewer", "ledger_review");
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
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
                new BadValue("operationKey that is empty", call -> call.with("operationKey", ""),
                        "VALIDATION_FAILED", List.of("operationKey")),
                new BadValue("reason of nine characters", call -> call.with("reason", "123456789"),
                        "REASON_INVALID", List.of("reason")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badValues")
    @DisplayName("MCW-018: a text, a fraction, a negative version, an empty operationKey and a short reason are refused "
            + "without coercion")
    void aBadValueIsRefused(BadValue bad, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();
        Args call = Args.from(session.mapper(),
                Calls.IssueSave.editTitle(seed.issue(), 1, "edited", Scenario.AUTHOR));

        ToolError error = session.failed("ledger_issue_save", bad.change().apply(call));

        assertThat(error.code()).isEqualTo(bad.token());
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactlyElementsOf(bad.paths());
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @Test
    @DisplayName("MCW-019: a verification through ledger_transition is refused by the schema")
    void transitionCannotVerify(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        ToolError error = session.failed("ledger_transition",
                Calls.Transition.of("issue", seed.resolved(), 2, "VERIFIED", Scenario.REVIEWER));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("MCW-019: a verification in a batch (author channel) is VERIFY_NEEDS_REVIEWER_LOGIN")
    void batchCannotVerify(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        ToolError error = session.failed("ledger_batch",
                Calls.Batch.of(Scenario.REVIEWER, Calls.Op.transition("issue", seed.resolved(), 2, "VERIFIED")));

        assertThat(error.code()).isEqualTo("VERIFY_NEEDS_REVIEWER_LOGIN");
        assertThat(Ledger.get(session, seed.resolved()).items()).extracting(item -> item.state())
                .containsExactly("RESOLVED");
    }

    @Test
    @DisplayName("MCW-019: ledger_review verifies an issue and a spec as the reviewer")
    void reviewVerifies(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);

        WriteOutputs.Transitioned verifiedIssue = session.ok("ledger_review",
                Calls.Review.of("issue", seed.resolved(), 2, "VERIFIED", Scenario.REVIEWER),
                WriteOutputs.Transitioned.class);

        assertThat(verifiedIssue).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new WriteOutputs.Transitioned(WriteStatus.COMMITTED, "issue", seed.resolved(),
                        "RESOLVED", "VERIFIED", 3, 0, Scenario.REVIEWER, "ledger_reviewer"));

        session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofSpec(seed.spec(), "spec-rev-verify", Scenario.AUTHOR),
                WriteOutputs.DeliverableRecorded.class);
        WriteOutputs.Transitioned verifiedSpec = session.ok("ledger_review",
                Calls.Review.of("spec", seed.spec(), 2, "VERIFIED", Scenario.REVIEWER),
                WriteOutputs.Transitioned.class);
        assertThat(verifiedSpec).usingRecursiveComparison().ignoringFields("historyId")
                .isEqualTo(new WriteOutputs.Transitioned(WriteStatus.COMMITTED, "spec", seed.spec(),
                        "COMPLETED", "VERIFIED", 3, 0, Scenario.REVIEWER, "ledger_reviewer"));
    }

    @Test
    void issueAndVerifyGrantsCanReturnACompletedTaskForRework(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        session.ok("ledger_transition",
                Calls.Transition.of("task", seed.task(), 1, "COMPLETED", Scenario.AUTHOR)
                        .withEvidence(Scenario.EVIDENCE),
                WriteOutputs.Transitioned.class);
        String reviewer = scenario.actorWith("return-reviewer", "issue", "verify");
        assertThat(session.failed("ledger_transition",
                Calls.Transition.of("task", seed.task(), 2, "ACTIVE", reviewer)).code())
                .isEqualTo("SESSION_NOT_GRANTED");
        var request = Calls.Review.of("task", seed.task(), 2, "ACTIVE", reviewer);
        var returned = session.ok("ledger_review", request, WriteOutputs.Transitioned.class);
        assertThat(returned.from()).isEqualTo("COMPLETED");
        assertThat(returned.to()).isEqualTo("ACTIVE");
        assertThat(returned.login()).isEqualTo("ledger_reviewer");
        assertThat(session.ok("ledger_review", request, WriteOutputs.Transitioned.class)).isEqualTo(returned);
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
    @MethodSource("org.veridger.mcp.support.ReadCase#all")
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
