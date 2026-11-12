package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.HistoryEntry;
import org.pgledger.domain.rule.ActorAssurance;
import org.pgledger.domain.rule.AttentionKind;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.rule.HistoryOp;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.LegacyActorId;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.SessionKey;
import org.pgledger.domain.value.Tokens;

/**
 * DOM-037 .. DOM-044: knowledge that callers used to re-derive (author parts, who may verify, which scope writes an
 * entity, which entity an attention kind is about, the reason length, the canonical order of a relation, the token
 * parser of closed sets) is answered by the value or the enum itself.
 */
class ValueKnowledgeTest {

    @Test
    @DisplayName("DOM-037 an author answers its vendor and its session; the owner is vendor user, session owner")
    void authorParts() {
        AuthorId session = AuthorId.of("claude:1234abcd");
        assertThat(session.vendor()).isEqualTo("claude");
        assertThat(session.session()).isEqualTo("1234abcd");
        assertThat(AuthorId.of("my-vendor:A.b_c-9").vendor()).isEqualTo("my-vendor");
        assertThat(AuthorId.of("my-vendor:A.b_c-9").session()).isEqualTo("A.b_c-9");
        assertThat(AuthorId.OWNER.vendor()).isEqualTo("user");
        assertThat(AuthorId.OWNER.session()).isEqualTo("owner");
        assertThat(AuthorId.of("a:" + "s".repeat(128)).session()).hasSize(128);
    }

    @Test
    @DisplayName("DOM-037 the session key of a stored actor answers the vendor as the history view showed it, and none for text without one")
    void sessionKeyVendor() {
        assertThat(new SessionKey("claude:1").vendor()).hasValue("claude");
        assertThat(new SessionKey("agent:codex:7").vendor()).hasValue("codex");
        assertThat(ActorId.ofStored("agent:claude:1").sessionKey().vendor()).hasValue("claude");
        assertThat(new LegacyActorId("someone").sessionKey().vendor()).isEmpty();
        assertThat(new LegacyActorId(":x").sessionKey().vendor()).isEmpty();
        assertThat(new LegacyActorId("has space:x").sessionKey().vendor()).isEmpty();
    }

    @Test
    @DisplayName("DOM-038 only the reviewer channel may verify")
    void channelMayVerify() {
        assertThat(Channel.REVIEWER.mayVerify()).isTrue();
        assertThat(Channel.AUTHOR.mayVerify()).isFalse();
    }

    @Test
    @DisplayName("DOM-039 every history entity names the scope that writes it; a state change reuses the same answer")
    void writeScopeOfEveryEntity() {
        assertThat(HistoryEntity.DEFECT.writeScope()).isEqualTo(Scope.DEFECT);
        assertThat(HistoryEntity.TASK.writeScope()).isEqualTo(Scope.TASK);
        assertThat(HistoryEntity.CONDITION.writeScope()).isEqualTo(Scope.TASK);
        assertThat(HistoryEntity.DEFECT_FIX.writeScope()).isEqualTo(Scope.FIX);
        assertThat(HistoryEntity.DEFECT_TASK.writeScope()).isEqualTo(Scope.DEFECT);
        assertThat(HistoryEntity.DEFECT_RELATION.writeScope()).isEqualTo(Scope.DEFECT);
        assertThat(HistoryEntity.CONDITION_LINK.writeScope()).isEqualTo(Scope.TASK);
        assertThat(HistoryEntity.DEFECT_CASE.writeScope()).isEqualTo(Scope.DEFECT);
        for (HistoryEntity entity : List.of(HistoryEntity.DEFECT, HistoryEntity.TASK, HistoryEntity.CONDITION)) {
            assertThat(Scope.forTransition(entity, DefectStatus.OPEN, DefectStatus.DISPUTED))
                    .isEqualTo(entity.writeScope());
        }
    }

    @Test
    @DisplayName("DOM-040 an attention kind answers the entity it is about, and the kinds of an entity are listed once")
    void attentionKindEntity() {
        assertThat(AttentionKind.OPEN_WITH_FIX.entity()).isEqualTo(HistoryEntity.DEFECT);
        assertThat(AttentionKind.FIXED_WITHOUT_COMMIT.entity()).isEqualTo(HistoryEntity.DEFECT);
        assertThat(AttentionKind.OPEN_NO_OWNER.entity()).isEqualTo(HistoryEntity.DEFECT);
        assertThat(AttentionKind.TASK_UNKNOWN.entity()).isEqualTo(HistoryEntity.TASK);
        assertThat(AttentionKind.DONE_NOT_VERIFIED.entity()).isEqualTo(HistoryEntity.TASK);
        assertThat(AttentionKind.of(HistoryEntity.DEFECT)).containsExactly(AttentionKind.OPEN_WITH_FIX,
                AttentionKind.FIXED_WITHOUT_COMMIT, AttentionKind.OPEN_NO_OWNER);
        assertThat(AttentionKind.of(HistoryEntity.TASK)).containsExactly(AttentionKind.TASK_UNKNOWN,
                AttentionKind.DONE_NOT_VERIFIED);
        assertThat(AttentionKind.of(HistoryEntity.CONDITION)).isEmpty();
        for (AttentionKind kind : AttentionKind.values()) {
            assertThat(AttentionKind.of(kind.entity())).contains(kind);
        }
    }

    @Test
    @DisplayName("DOM-041 the reason rule lives in Reason: the history entry and the value use the same bounds")
    void reasonBoundsAreOnePlace() {
        assertThat(Reason.validated("  " + "x".repeat(Reason.MIN) + "  ")).isEqualTo("x".repeat(Reason.MIN));
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.validated("x".repeat(Reason.MIN - 1)));
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.validated("x".repeat(Reason.MAX + 1)));
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.validated(null));
        for (int length : new int[] {Reason.MIN - 1, Reason.MIN, Reason.MAX, Reason.MAX + 1}) {
            String reason = "x".repeat(length);
            if (length >= Reason.MIN && length <= Reason.MAX) {
                assertThat(create(reason).reason()).isEqualTo(reason);
            } else {
                Expect.failsWith(ValidationCode.REASON_INVALID, () -> create(reason));
            }
        }
    }

    private static HistoryEntry create(String reason) {
        return new HistoryEntry(null, DomainFixtures.NOW, AuthorId.OWNER, Channel.AUTHOR.login(),
                ActorAssurance.DECLARED, HistoryEntity.DEFECT, "E2E-D-0001", HistoryOp.CREATE, null, null,
                JsonDocument.EMPTY_OBJECT, null, reason, null, null, null, new RequestId(UUID.randomUUID()), null);
    }

    @Test
    @DisplayName("DOM-042 the canonical order of a relation pair is one rule, used by the relation and by callers that need the pair")
    void relationPairOrder() {
        DefectId one = DefectId.of("E2E-D-0001");
        DefectId two = DefectId.of("E2E-D-0002");
        assertThat(DefectRelation.Pair.ordered(two, one)).isEqualTo(new DefectRelation.Pair(one, two));
        assertThat(DefectRelation.Pair.ordered(one, two)).isEqualTo(new DefectRelation.Pair(one, two));
        Expect.invalid(() -> DefectRelation.Pair.ordered(one, one));
        DefectRelation relation = new DefectRelation(two, one, CodeValue.of("same_site"), AuthorId.OWNER);
        assertThat(relation.a()).isEqualTo(one);
        assertThat(relation.b()).isEqualTo(two);
        // ids of different width order as text, as the stored pair always did
        assertThat(DefectRelation.Pair.ordered(DefectId.of("E2E-D-10000"), DefectId.of("E2E-D-9999")).first().value())
                .isEqualTo("E2E-D-10000");
    }

    private enum Plain {
        FIRST, SECOND
    }

    @Test
    @DisplayName("DOM-043 closed sets are parsed by token or by constant name in one class; an unknown value lists the allowed ones")
    void tokensParseByNameToo() {
        assertThat(Tokens.parseName("kind", "SECOND", Plain.values())).isEqualTo(Plain.SECOND);
        LedgerException unknown = Expect.invalid(() -> Tokens.parseName("kind", "second", Plain.values()));
        assertThat(((ValidationException) unknown).issues().getFirst().allowed()).containsExactly("FIRST", "SECOND");
        assertThat(((ValidationException) unknown).issues().getFirst().reasonArgs()).containsExactly("second");
        LedgerException none = Expect.invalid(() -> Tokens.parseName("kind", null, Plain.values()));
        assertThat(((ValidationException) none).issues().getFirst().reasonArgs()).containsExactly("null");
        assertThat(Tokens.parseName("kind", "OPEN_WITH_FIX", AttentionKind.values()))
                .isEqualTo(AttentionKind.OPEN_WITH_FIX);
    }

    @Test
    @DisplayName("DOM-044 a patch answers its value, or null when absent, without orElse(null)")
    void patchOrNull() {
        assertThat(Patch.<String>absent().orNull()).isNull();
        assertThat(Patch.set("x").orNull()).isEqualTo("x");
        assertThat(Patch.<String>set(null).orNull()).isNull();
    }

    @Test
    @DisplayName("DOM-045 a stored state text answers whether it is a verification state, and a history entry whether it changes one")
    void verificationOfStoredStates() {
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "VERIFIED")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.DEFECT, "VERIFIED")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.CONDITION, "MET")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.CONDITION, "NOT_MET")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.CONDITION, "UNKNOWN")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "ACTIVE")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "MET")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "not a state")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, null)).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.DEFECT_FIX, "VERIFIED")).isFalse();

        assertThat(transition("SOURCE_FIXED", "VERIFIED", Channel.REVIEWER).involvesVerification()).isTrue();
        assertThat(transition("VERIFIED", "OPEN", Channel.AUTHOR).involvesVerification()).isTrue();
        assertThat(transition("OPEN", "DISPUTED", Channel.AUTHOR).involvesVerification()).isFalse();
        assertThat(create("a reason of enough length").involvesVerification()).isFalse();
        // entering a verification state through the author channel is refused
        Expect.invalid(() -> transition("SOURCE_FIXED", "VERIFIED", Channel.AUTHOR));
    }

    private static HistoryEntry transition(String from, String to, Channel channel) {
        return new HistoryEntry(null, DomainFixtures.NOW, AuthorId.OWNER, channel.login(), ActorAssurance.DECLARED,
                HistoryEntity.DEFECT, "E2E-D-0001", HistoryOp.TRANSITION, from, to, JsonDocument.EMPTY_OBJECT,
                JsonDocument.EMPTY_OBJECT, "a reason of enough length", null, null, null,
                new RequestId(UUID.randomUUID()), null);
    }
}
