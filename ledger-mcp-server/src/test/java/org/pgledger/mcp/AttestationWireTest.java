package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.BatchResults;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.KeyRing;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.support.Seed;
import org.pgledger.mcp.support.WriteCase;
import org.pgledger.mcp.tool.WriteOutputs;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MCW-030 .. MCW-039: the signed attestation as the server sees it on the wire. A caller that holds the bearer token and
 * nothing else (no hook, no key) writes nothing as anybody: the plain {@code <vendor>:<session-id>} that the old hook
 * sent, a forged signature, a signature of another author, a token for another tool or request id, a token of another
 * time, an author without a registered key, a revoked key. The calls are sent with {@link McpSession#callUnsigned}, so
 * the test decides exactly what the attestation is.
 */
@McpServerTest
class AttestationWireTest {

    private static final String WHOAMI = "ledger_whoami";

    /** The call of the case as a flat object: with this request id (not whoami) and this attestation. */
    private static Args callOf(McpSession session, WriteCase write, Seed seed, String requestId, String attestation) {
        Args call = Args.from(session.mapper(), write.call(seed));
        if (!WHOAMI.equals(write.tool())) {
            call = call.with("request_id", requestId);
        }
        return call.with("_hostAttestation", attestation);
    }

    private static String requestIdFor(WriteCase write) {
        return WHOAMI.equals(write.tool()) ? "" : UUID.randomUUID().toString();
    }

    private static ToolError refused(McpSession session, WriteCase write, Args call, String code, String... mustNotShow) {
        CallToolResult result = session.callUnsigned(write.tool(), call);
        assertThat(result.isError()).as("%s must be refused with %s", write.tool(), code).isTrue();
        ToolError error = session.error(result);
        assertThat(error.code()).isEqualTo(code);
        for (String secret : mustNotShow) {
            assertThat(error.message()).doesNotContain(secret);
            assertThat(error.details().toString()).doesNotContain(secret);
        }
        return error;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-030: the old attestation, a bare <vendor>:<session-id> as the author's id is known from the history, is ATTESTATION_UNSIGNED for all ten tools and writes nothing")
    void unsignedOldFormat(WriteCase write, McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        int before = Ledger.history(session, seed.project()).size();

        // the author id of a session is public: ledger_history lists it as author_id
        Args call = callOf(session, write, seed, requestIdFor(write), write.actor());
        ToolError error = refused(session, write, call, "ATTESTATION_UNSIGNED");

        assertThat(error.message()).contains("not signed");
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-031: a forged signature, and a signature made with the key of another registered author, is ATTESTATION_SIGNATURE_INVALID for all ten tools and writes nothing")
    void forgedSignature(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        keys.enroll(Scenario.NOGRANT);
        int before = Ledger.history(session, seed.project()).size();
        Instant now = keys.clock().instant();
        String requestId = requestIdFor(write);

        String strangersKey = KeyRing.token(KeyRing.newPair(), write.actor(), write.tool(), requestId, now);
        String anotherAuthorsKey = KeyRing.token(keys.pairOf(Scenario.NOGRANT), write.actor(), write.tool(), requestId,
                now);
        for (String forged : List.of(strangersKey, anotherAuthorsKey)) {
            refused(session, write, callOf(session, write, seed, requestId, forged), "ATTESTATION_SIGNATURE_INVALID",
                    forged);
        }
        assertThat(Ledger.history(session, seed.project())).hasSize(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-032: a token signed for another tool is ATTESTATION_BINDING_MISMATCH (tool) for all ten tools")
    void anotherTool(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        String otherTool = WHOAMI.equals(write.tool()) ? "ledger_save" : WHOAMI;
        String requestId = requestIdFor(write);

        String token = keys.token(write.actor(), otherTool, requestId);
        ToolError error = refused(session, write, callOf(session, write, seed, requestId, token),
                "ATTESTATION_BINDING_MISMATCH", token);

        assertThat(error.details()).containsEntry("field", List.of("tool"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.WriteCase#all")
    @DisplayName("MCW-033: a token signed for another request_id, or for none, is ATTESTATION_BINDING_MISMATCH (request_id) for all ten tools")
    void anotherRequestId(WriteCase write, McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        keys.enroll(write.actor());
        String sent = WHOAMI.equals(write.tool()) ? "" : UUID.randomUUID().toString();
        // a token for a request id that the call does not carry (for whoami: any id, it carries none)
        String signedFor = UUID.randomUUID().toString();

        String token = keys.token(write.actor(), write.tool(), signedFor);
        ToolError error = refused(session, write, callOf(session, write, seed, sent, token),
                "ATTESTATION_BINDING_MISMATCH", token);
        assertThat(error.details()).containsEntry("field", List.of("request_id"));
        if (!WHOAMI.equals(write.tool())) {
            String withoutId = keys.token(write.actor(), write.tool(), "");
            refused(session, write, callOf(session, write, seed, sent, withoutId), "ATTESTATION_BINDING_MISMATCH");
        }
    }

    @Test
    @DisplayName("MCW-034: a token issued more than the window (5 minutes) before or after the server's time is ATTESTATION_TIME_WINDOW; one on the edge of the window is accepted")
    void timeWindow(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        keys.enroll(save.actor());
        Instant now = keys.clock().instant();
        for (long offset : new long[] {-600, 600, -3600 * 24, 3600 * 24}) {
            String requestId = UUID.randomUUID().toString();
            String token = KeyRing.token(keys.pairOf(save.actor()), save.actor(), save.tool(), requestId,
                    now.plusSeconds(offset));
            ToolError error = refused(session, save, callOf(session, save, seed, requestId, token),
                    "ATTESTATION_TIME_WINDOW", token);
            assertThat(error.details()).containsEntry("window_seconds", List.of("300"));
        }
        // 240 seconds either way is inside the window (the clock of the test runs on while the calls are made)
        for (long offset : new long[] {-240, 240}) {
            String requestId = UUID.randomUUID().toString();
            String token = KeyRing.token(keys.pairOf(save.actor()), save.actor(), save.tool(), requestId,
                    now.plusSeconds(offset));
            assertThat(session.callUnsigned(save.tool(), callOf(session, save, seed, requestId, token)).isError())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("MCW-035: an author whose key was never registered is SESSION_KEY_NOT_REGISTERED and the message tells to show the key to the owner")
    void keyNotRegistered(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        String stranger = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        scenario.grant(stranger, "defect");
        String requestId = UUID.randomUUID().toString();
        String token = KeyRing.token(KeyRing.newPair(), stranger, save.tool(), requestId,
                keys.clock().instant());

        ToolError error = refused(session, save, callOf(session, save, seed, requestId, token),
                "SESSION_KEY_NOT_REGISTERED", token);

        assertThat(error.message()).contains(stranger).contains("session-key");
    }

    @Test
    @DisplayName("MCW-036: a revoked key is SESSION_KEY_NOT_REGISTERED; after another key is registered, a signature of the old key is ATTESTATION_SIGNATURE_INVALID and the new key writes")
    void revokedKey(McpSession session, Scenario scenario, @Autowired KeyRing keys,
            @Autowired SessionKeyService owner) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        String author = scenario.actorWith("rk", "defect");
        keys.enroll(author);

        assertThat(owner.revoke(author)).isTrue();
        String first = UUID.randomUUID().toString();
        refused(session, save, callOf(session, save, seed, first, keys.token(author, save.tool(), first)),
                "SESSION_KEY_NOT_REGISTERED");

        KeyPair replacement = KeyRing.newPair();
        owner.register(new SessionKeyService.RegisterCommand(author, PublicKeys.encode(replacement.getPublic()), null));
        String second = UUID.randomUUID().toString();
        refused(session, save, callOf(session, save, seed, second, keys.token(author, save.tool(), second)),
                "ATTESTATION_SIGNATURE_INVALID");
        String third = UUID.randomUUID().toString();
        String token = KeyRing.token(replacement, author, save.tool(), third, keys.clock().instant());
        CallToolResult written = session.callUnsigned(save.tool(), callOf(session, save, seed, third, token));
        assertThat(written.isError()).isFalse();
        assertThat(session.read(written, WriteOutputs.Saved.class).author()).isEqualTo(author);
    }

    @Test
    @DisplayName("MCW-037: a signed call that is captured and sent again returns the stored response; with other content under the same request_id it is REQUEST_CONFLICT; the same token for another request_id is a binding mismatch")
    void replayOfASignedCall(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        WriteCase save = WriteCase.all().getFirst();
        String author = scenario.actorWith("rp", "defect");
        keys.enroll(author);
        String requestId = UUID.randomUUID().toString();
        String token = keys.token(author, save.tool(), requestId);
        Args captured = Args.of().with("entity", "defect").with("reason", Scenario.REASON)
                .with("fields", Fields.defect(scenario.projectId(), "captured")).with("request_id", requestId)
                .with("_hostAttestation", token);

        WriteOutputs.Saved first = session.ok(save.tool(), captured, WriteOutputs.Saved.class);
        WriteOutputs.Saved again = session.ok(save.tool(), captured, WriteOutputs.Saved.class);
        assertThat(again).isEqualTo(first);

        ToolError conflict = session.failed(save.tool(), captured.with("fields",
                Fields.defect(scenario.projectId(), "forged content")));
        assertThat(conflict.code()).isEqualTo("REQUEST_CONFLICT");

        ToolError moved = session.failed(save.tool(), captured.with("request_id", UUID.randomUUID().toString()));
        assertThat(moved.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(1);
    }

    @Test
    @DisplayName("MCW-038: whoami of a signed session lists its grants; of a session without a registered key it is SESSION_KEY_NOT_REGISTERED; of a malformed value it still answers author_format_ok=false")
    void whoami(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("who", "defect", "task");
        keys.enroll(author);
        String token = keys.token(author, WHOAMI, "");
        WriteOutputs.Whoami who = session.ok(WHOAMI, Args.of().with("_hostAttestation", token),
                WriteOutputs.Whoami.class);
        assertThat(who.author()).isEqualTo(author);
        assertThat(who.grants()).hasSize(2);

        String stranger = "claude:nokey" + UUID.randomUUID().toString().substring(0, 8);
        String forged = KeyRing.token(KeyRing.newPair(), stranger, WHOAMI, "", keys.clock().instant());
        assertThat(session.failed(WHOAMI, Args.of().with("_hostAttestation", forged)).code())
                .isEqualTo("SESSION_KEY_NOT_REGISTERED");
        assertThat(session.error(session.callUnsigned(WHOAMI, Args.of().with("_hostAttestation", author))).code())
                .isEqualTo("ATTESTATION_UNSIGNED");
        assertThat(session.ok(WHOAMI, Args.of().with("_hostAttestation", "claude"), WriteOutputs.Whoami.class)
                .authorFormatOk()).isFalse();
    }

    /** The token of the hook for exactly these arguments (the hook signs after it has put the request id in). */
    private static String signedFor(KeyRing keys, String author, String tool, String requestId, Args arguments) {
        return keys.token(author, tool, requestId);
    }

    private static Args batchCall(McpSession session, String requestId, Calls.Op... ops) {
        return Args.from(session.mapper(), Calls.Batch.of(null, ops)).with("request_id", requestId);
    }

    @Test
    @DisplayName("MCW-041: the token of a batch cannot be sent again with other operations: new operations, an extra one or a changed one are ATTESTATION_BINDING_MISMATCH (arguments) and write nothing")
    void batchTokenCannotCarryOtherOperations(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("br", "defect");
        keys.enroll(author);
        String project = scenario.projectId();
        String batchId = UUID.randomUUID().toString();
        Args legitimate = batchCall(session, batchId, Calls.Op.save("defect", Fields.defect(project, "one")));
        String token = signedFor(keys, author, "ledger_batch", batchId, legitimate);

        List<Args> variants = List.of(
                batchCall(session, batchId, Calls.Op.save("defect", Fields.defect(project, "one")),
                        Calls.Op.save("defect", Fields.defect(project, "extra"))),
                batchCall(session, batchId, Calls.Op.save("defect", Fields.defect(project, "changed"))),
                batchCall(session, batchId, Calls.Op.save("defect", Fields.defect(project, "one"))
                        .withRequestId(UUID.randomUUID().toString())));
        int before = Ledger.history(session, project).size();
        for (Args variant : variants) {
            ToolError error = session.failed("ledger_batch", variant.with("_hostAttestation", token));
            assertThat(error.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
            assertThat(error.details()).containsEntry("field", List.of("arguments"));
            assertThat(error.message()).doesNotContain(token);
        }
        assertThat(Ledger.history(session, project)).hasSize(before);

        session.ok("ledger_batch", legitimate.with("_hostAttestation", token), BatchResults.class);
        session.ok("ledger_batch", legitimate.with("_hostAttestation", token), BatchResults.class);
        assertThat(Ledger.history(session, project)).hasSize(before + 1);
    }

    @Test
    @DisplayName("MCW-042: the token of a save cannot be used for other content, whether the legitimate call came first or not")
    void saveTokenCannotCarryOtherContent(McpSession session, Scenario scenario, @Autowired KeyRing keys) {
        String author = scenario.actorWith("pl", "defect");
        keys.enroll(author);
        String requestId = UUID.randomUUID().toString();
        Args signed = Args.of().with("entity", "defect").with("reason", Scenario.REASON)
                .with("fields", Fields.defect(scenario.projectId(), "signed")).with("request_id", requestId);
        Args other = signed.with("fields", Fields.defect(scenario.projectId(), "attacker content"));
        String token = signedFor(keys, author, "ledger_save", requestId, signed);
        int before = Ledger.history(session, scenario.projectId()).size();

        // the attacker is first: the other content must not win the race for the request id
        ToolError raced = session.failed("ledger_save", other.with("_hostAttestation", token));
        assertThat(raced.code()).isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(raced.details()).containsEntry("field", List.of("arguments"));
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(before);

        WriteOutputs.Saved written = session.ok("ledger_save", signed.with("_hostAttestation", token),
                WriteOutputs.Saved.class);
        assertThat(written.author()).isEqualTo(author);
        assertThat(session.failed("ledger_save", other.with("_hostAttestation", token)).code())
                .isEqualTo("ATTESTATION_BINDING_MISMATCH");
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(before + 1);
    }

    @Test
    @DisplayName("MCC-028: no tool registers, revokes or lists keys or grants: the owner does that in the admin web and the CLI only")
    void noKeyOrGrantTool(McpSession session) {
        assertThat(session.tools()).extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                .noneMatch(name -> name.contains("key") || name.contains("grant") || name.contains("register")
                        || name.contains("revoke") || name.contains("attest"));
    }

    @Test
    @DisplayName("MCW-039: a text that has the prefix of a token but is not a canonical token is ATTESTATION_MALFORMED and the error never contains it")
    void malformedToken(McpSession session, Scenario scenario) {
        Seed seed = Seed.create(session, scenario);
        WriteCase save = WriteCase.all().getFirst();
        for (String text : List.of("pgla1.", "pgla1.a.b.c.d.e", "pgla1.!.!.!.1.!", "pgla1." + "A".repeat(5000))) {
            refused(session, save, callOf(session, save, seed, UUID.randomUUID().toString(), text),
                    "ATTESTATION_MALFORMED", text);
        }
    }
}
