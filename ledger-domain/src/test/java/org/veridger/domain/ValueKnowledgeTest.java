// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.domain.error.LedgerException;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.HistoryEntry;
import org.veridger.domain.model.IssueRelation;
import org.veridger.domain.rule.ActorAssurance;
import org.veridger.domain.rule.AttentionKind;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.HistoryOp;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.LifecycleState;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.ActorId;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.ImportedActorId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.JsonDocument;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Patch;
import org.veridger.domain.value.Reason;
import org.veridger.domain.value.SessionKey;
import org.veridger.domain.value.Tokens;

/** DOM-037 .. DOM-045: domain knowledge helpers on values, rules, and entities. */
class ValueKnowledgeTest {

    @Test
    @DisplayName("DOM-037 an author answers its client and its session; the owner is client user, session owner")
    void authorParts() {
        AuthorId session = AuthorId.of("claude:1234abcd");
        assertThat(session.client()).isEqualTo("claude");
        assertThat(session.session()).isEqualTo("1234abcd");
        assertThat(AuthorId.of("my-vendor:A.b_c-9").client()).isEqualTo("my-vendor");
        assertThat(AuthorId.of("my-vendor:A.b_c-9").session()).isEqualTo("A.b_c-9");
        assertThat(AuthorId.OWNER.client()).isEqualTo("user");
        assertThat(AuthorId.OWNER.session()).isEqualTo("owner");
        assertThat(AuthorId.of("a:" + "s".repeat(128)).session()).hasSize(128);
    }

    @Test
    @DisplayName("DOM-037 session key extracts vendor cleanly")
    void sessionKeyClient() {
        assertThat(new SessionKey("claude:1").vendor()).hasValue("claude");
        assertThat(new SessionKey("codex:7").vendor()).hasValue("codex");
        assertThat(ActorId.ofStored("claude:1").sessionKey().vendor()).hasValue("claude");
        assertThat(new ImportedActorId("someone").sessionKey().vendor()).isEmpty();
        assertThat(new ImportedActorId(":x").sessionKey().vendor()).isEmpty();
        assertThat(new ImportedActorId("has space:x").sessionKey().vendor()).isEmpty();
    }

    @Test
    @DisplayName("DOM-038 only the reviewer channel may verify")
    void channelMayVerify() {
        assertThat(Channel.REVIEWER.mayVerify()).isTrue();
        assertThat(Channel.AUTHOR.mayVerify()).isFalse();
    }

    @Test
    @DisplayName("DOM-039 every history entity names the scope that writes it")
    void writeScopeOfEveryEntity() {
        assertThat(HistoryEntity.ISSUE.createScope()).isEqualTo(Scope.ISSUE_CREATE);
        assertThat(HistoryEntity.SPEC.createScope()).isEqualTo(Scope.SPEC_CREATE);
        assertThat(HistoryEntity.TASK.createScope()).isEqualTo(Scope.TASK_CREATE);
        assertThat(HistoryEntity.CRITERION.createScope()).isEqualTo(Scope.CRITERION_CREATE);
        assertThat(HistoryEntity.ISSUE.updateScope()).isEqualTo(Scope.ISSUE_UPDATE);
        assertThat(HistoryEntity.SPEC.updateScope()).isEqualTo(Scope.SPEC_UPDATE);
        assertThat(HistoryEntity.TASK.updateScope()).isEqualTo(Scope.TASK_UPDATE);
        assertThat(HistoryEntity.CRITERION.updateScope()).isEqualTo(Scope.CRITERION_UPDATE);
        assertThat(HistoryEntity.DELIVERABLE.updateScope()).isEqualTo(Scope.DELIVERABLE_EDIT);
        assertThat(HistoryEntity.ISSUE_TASK.updateScope()).isEqualTo(Scope.ISSUE_UPDATE);
        assertThat(HistoryEntity.ISSUE_SPEC.updateScope()).isEqualTo(Scope.SPEC_UPDATE);
        assertThat(HistoryEntity.SPEC_TASK.updateScope()).isEqualTo(Scope.SPEC_UPDATE);
        assertThat(HistoryEntity.ISSUE_RELATION.updateScope()).isEqualTo(Scope.ISSUE_UPDATE);
        assertThat(HistoryEntity.CRITERION_LINK.updateScope()).isEqualTo(Scope.CRITERION_UPDATE);
        assertThat(HistoryEntity.ISSUE_CASE.updateScope()).isEqualTo(Scope.ISSUE_UPDATE);
        for (HistoryEntity entity : List.of(HistoryEntity.ISSUE, HistoryEntity.SPEC, HistoryEntity.TASK,
                HistoryEntity.CRITERION)) {
            assertThat(Scope.forTransition(entity, IssueStatus.OPEN, IssueStatus.DISPUTED))
                    .isEqualTo(entity.updateScope());
        }
    }

    @Test
    @DisplayName("DOM-040 attention kinds cover issue, spec, and task")
    void attentionKindEntity() {
        assertThat(AttentionKind.OPEN_WITH_DELIVERABLE.entity()).isEqualTo(HistoryEntity.ISSUE);
        assertThat(AttentionKind.RESOLVED_WITHOUT_DELIVERABLE.entity()).isEqualTo(HistoryEntity.ISSUE);
        assertThat(AttentionKind.OPEN_NO_OWNER.entity()).isEqualTo(HistoryEntity.ISSUE);
        assertThat(AttentionKind.SPEC_COMPLETED_NOT_VERIFIED.entity()).isEqualTo(HistoryEntity.SPEC);
        assertThat(AttentionKind.TASK_UNKNOWN.entity()).isEqualTo(HistoryEntity.TASK);
        assertThat(AttentionKind.DONE_NOT_VERIFIED.entity()).isEqualTo(HistoryEntity.TASK);
        assertThat(AttentionKind.of(HistoryEntity.ISSUE)).containsExactly(AttentionKind.OPEN_WITH_DELIVERABLE,
                AttentionKind.RESOLVED_WITHOUT_DELIVERABLE, AttentionKind.OPEN_NO_OWNER);
        assertThat(AttentionKind.of(HistoryEntity.SPEC)).containsExactly(AttentionKind.SPEC_COMPLETED_NOT_VERIFIED);
        assertThat(AttentionKind.of(HistoryEntity.TASK)).containsExactly(AttentionKind.TASK_UNKNOWN,
                AttentionKind.DONE_NOT_VERIFIED);
        assertThat(AttentionKind.of(HistoryEntity.CRITERION)).isEmpty();
        for (AttentionKind kind : AttentionKind.values()) {
            assertThat(AttentionKind.of(kind.entity())).contains(kind);
        }
    }

    @Test
    @DisplayName("DOM-041 the reason rule lives in Reason")
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
                ActorAssurance.DECLARED, HistoryEntity.ISSUE, "E2E-I-0001", HistoryOp.CREATE, null, null,
                JsonDocument.EMPTY_OBJECT, null, reason, null, null, null,
                new OperationKey(UUID.randomUUID().toString()), null, null);
    }

    @Test
    @DisplayName("DOM-042 relation pair ordering honors CodeDirection")
    void relationPairOrder() {
        IssueId one = IssueId.of("E2E-I-0001");
        IssueId two = IssueId.of("E2E-I-0002");
        assertThat(IssueRelation.Pair.of(two, one, CodeDirection.CANONICAL)).isEqualTo(new IssueRelation.Pair(one, two));
        assertThat(IssueRelation.Pair.of(one, two, CodeDirection.CANONICAL)).isEqualTo(new IssueRelation.Pair(one, two));
        assertThat(IssueRelation.Pair.of(two, one, CodeDirection.GIVEN)).isEqualTo(new IssueRelation.Pair(two, one));
        assertThat(IssueRelation.Pair.of(one, two, CodeDirection.GIVEN)).isEqualTo(new IssueRelation.Pair(one, two));
        for (CodeDirection direction : CodeDirection.values()) {
            Expect.invalid(() -> IssueRelation.Pair.of(one, one, direction));
            Expect.invalid(() -> IssueRelation.between(one, one, CodeValue.of("same_site"), direction, AuthorId.OWNER));
        }
        IssueRelation relation = IssueRelation.between(two, one, CodeValue.of("same_site"), CodeDirection.CANONICAL,
                AuthorId.OWNER);
        assertThat(relation.a()).isEqualTo(one);
        assertThat(relation.b()).isEqualTo(two);
        IssueRelation directed = IssueRelation.between(two, one, CodeValue.of("caused_by"), CodeDirection.GIVEN,
                AuthorId.OWNER);
        assertThat(directed.a()).isEqualTo(two);
        assertThat(directed.b()).isEqualTo(one);
        assertThat(IssueRelation.Pair.of(IssueId.of("E2E-I-10000"), IssueId.of("E2E-I-9999"), CodeDirection.CANONICAL)
                .first().value()).isEqualTo("E2E-I-10000");
        assertThat(IssueRelation.Pair.of(IssueId.of("E2E-I-9999"), IssueId.of("E2E-I-10000"), CodeDirection.GIVEN)
                .first().value()).isEqualTo("E2E-I-9999");
    }

    private enum Plain {
        FIRST, SECOND
    }

    @Test
    @DisplayName("DOM-043 closed sets are parsed by token or constant name")
    void tokensParseByNameToo() {
        assertThat(Tokens.parseName("kind", "SECOND", Plain.values())).isEqualTo(Plain.SECOND);
        LedgerException unknown = Expect.invalid(() -> Tokens.parseName("kind", "second", Plain.values()));
        assertThat(((ValidationException) unknown).issues().getFirst().allowed()).containsExactly("FIRST", "SECOND");
        assertThat(((ValidationException) unknown).issues().getFirst().reasonArgs()).containsExactly("second");
        LedgerException none = Expect.invalid(() -> Tokens.parseName("kind", null, Plain.values()));
        assertThat(((ValidationException) none).issues().getFirst().reasonArgs()).containsExactly("null");
        assertThat(Tokens.parseName("kind", "OPEN_WITH_DELIVERABLE", AttentionKind.values()))
                .isEqualTo(AttentionKind.OPEN_WITH_DELIVERABLE);
    }

    @Test
    @DisplayName("DOM-044 patch orNull")
    void patchOrNull() {
        assertThat(Patch.<String>absent().orNull()).isNull();
        assertThat(Patch.set("x").orNull()).isEqualTo("x");
        assertThat(Patch.<String>set(null).orNull()).isNull();
    }

    @Test
    @DisplayName("DOM-045 verification state check across all entities")
    void verificationOfStoredStates() {
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "VERIFIED")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.SPEC, "VERIFIED")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.ISSUE, "VERIFIED")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.CRITERION, "MET")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.CRITERION, "NOT_MET")).isTrue();
        assertThat(LifecycleState.isVerification(HistoryEntity.CRITERION, "UNKNOWN")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.SPEC, "COMPLETED")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "ACTIVE")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "MET")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, "not a state")).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.TASK, null)).isFalse();
        assertThat(LifecycleState.isVerification(HistoryEntity.DELIVERABLE, "VERIFIED")).isFalse();

        assertThat(transition("RESOLVED", "VERIFIED", Channel.REVIEWER).involvesVerification()).isTrue();
        assertThat(transition("VERIFIED", "OPEN", Channel.AUTHOR).involvesVerification()).isTrue();
        assertThat(transition("OPEN", "DISPUTED", Channel.AUTHOR).involvesVerification()).isFalse();
        assertThat(create("a reason of enough length").involvesVerification()).isFalse();
        Expect.invalid(() -> transition("RESOLVED", "VERIFIED", Channel.AUTHOR));
    }

    private static HistoryEntry transition(String from, String to, Channel channel) {
        return new HistoryEntry(null, DomainFixtures.NOW, AuthorId.OWNER, channel.login(), ActorAssurance.DECLARED,
                HistoryEntity.ISSUE, "E2E-I-0001", HistoryOp.TRANSITION, from, to, JsonDocument.EMPTY_OBJECT,
                JsonDocument.EMPTY_OBJECT, "a reason of enough length", null, null, null,
                new OperationKey(UUID.randomUUID().toString()), null, null);
    }
}
