package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.EntityId;
import org.pgledger.domain.value.LegacyActorId;
import org.pgledger.domain.value.ProjectCode;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.SessionKey;
import org.pgledger.domain.value.TaskId;

/** DOM-001 .. DOM-008 and DOM-036: identities and ids. */
class IdentityTest {

    @Test
    void dom001_validAuthorIdsAreAccepted() {
        for (String ok : List.of("claude:1234abcd", "a:x", "user:owner", "a".repeat(32) + ":x",
                "claude:" + "s".repeat(128), "my-vendor:A.b_c-9")) {
            assertThat(AuthorId.of(ok).value()).isEqualTo(ok);
        }
    }

    @Test
    void dom002_invalidAuthorIdsAreRejectedAndAbsentIsADifferentFailure() {
        for (String bad : List.of("claude", "user:someone", "Claude:x", ":x", "a:", "a".repeat(33) + ":x",
                "claude:" + "s".repeat(129), "claude:a b", "claude:a:b", "claude:한글", "user:owner ", "")) {
            Expect.failsWith(TrustCode.AUTHOR_ID_FORMAT, () -> AuthorId.of(bad));
        }
        Expect.failsWith(TrustCode.HOST_ATTESTATION_REQUIRED, () -> AuthorId.of(null));
    }

    @Test
    void dom003_sessionKeyStripsTheLegacyPrefixAndKeepsTheVendor() {
        assertThat(new SessionKey("agent:claude:1")).isEqualTo(new SessionKey("claude:1"));
        assertThat(new SessionKey("claude:1")).isNotEqualTo(new SessionKey("codex:1"));
        assertThat(ActorId.ofStored("agent:claude:1")).isInstanceOf(LegacyActorId.class);
        assertThat(ActorId.ofStored("agent:claude:1").sessionKey()).isEqualTo(AuthorId.of("claude:1").sessionKey());
        assertThat(ActorId.ofStored("claude:1")).isInstanceOf(AuthorId.class);
    }

    @Test
    void dom004_projectCodes() {
        for (String ok : List.of("E2E", "A", "A" + "B".repeat(31))) {
            assertThat(ProjectCode.of(ok).value()).isEqualTo(ok);
        }
        for (String bad : List.of("A" + "B".repeat(32), "e2e", "1AB", "A-B", "", " A")) {
            Expect.invalid(() -> ProjectCode.of(bad));
        }
        assertThat(ProjectId.of("e2e").value()).isEqualTo("e2e");
        Expect.invalid(() -> ProjectId.of("a b"));
        Expect.invalid(() -> ProjectId.of("x".repeat(65)));
    }

    @Test
    void dom005_defectIds() {
        assertThat(DefectId.of("E2E-D-0001").number()).isEqualTo(1);
        assertThat(DefectId.of("E2E-D-10000").number()).isEqualTo(10000);
        assertThat(DefectId.of("E2E-D-0001").projectCode()).isEqualTo(ProjectCode.of("E2E"));
        for (String bad : List.of("E2E-D-001", "e2e-D-0001", "E2E-D-", "E2E-T-0001", "")) {
            Expect.invalid(() -> DefectId.of(bad));
        }
        assertThat(DefectId.of(ProjectCode.of("E2E"), 1).value()).isEqualTo("E2E-D-0001");
        assertThat(DefectId.of(ProjectCode.of("E2E"), 9999).value()).isEqualTo("E2E-D-9999");
        assertThat(DefectId.of(ProjectCode.of("E2E"), 10000).value()).isEqualTo("E2E-D-10000");
    }

    @Test
    void dom006_taskIdsAreFreeTextButIssuedIdsFollowThePattern() {
        for (String ok : List.of("E2E-T-0001", "E2E-T-1", "E2E-T|PIPE")) {
            assertThat(TaskId.of(ok).value()).isEqualTo(ok);
        }
        Expect.invalid(() -> TaskId.of(" "));
        Expect.invalid(() -> TaskId.of(""));
        assertThat(TaskId.issue(ProjectCode.of("E2E"), 7).value()).isEqualTo("E2E-T-0007");
    }

    @Test
    void dom007_requestIdsAndDerivedIds() {
        Expect.invalid(() -> RequestId.parse("not-a-uuid"));
        Expect.invalid(() -> RequestId.parse("1-1-1-1-1"));
        RequestId batch = RequestId.parse(UUID.randomUUID().toString());
        assertThat(batch.derive(0)).isEqualTo(batch.derive(0));
        assertThat(batch.derive(0)).isNotEqualTo(batch.derive(1));
        RequestId other = RequestId.parse(UUID.randomUUID().toString());
        assertThat(batch.derive(0)).isNotEqualTo(other.derive(0));
    }

    @Test
    void dom008_reasonLengthCountsCharactersAfterTrim() {
        assertThat(Reason.of("x".repeat(10)).text()).hasSize(10);
        assertThat(Reason.of("x".repeat(1000)).text()).hasSize(1000);
        assertThat(Reason.of("가".repeat(10)).text()).hasSize(10);
        assertThat(Reason.of("  " + "x".repeat(10) + "  ").text()).isEqualTo("x".repeat(10));
        for (String bad : List.of("x".repeat(9), "x".repeat(1001), "x".repeat(9) + "   ", "", "          ")) {
            Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.of(bad));
        }
        Expect.failsWith(ValidationCode.REASON_INVALID, () -> Reason.of(null));
    }

    @Test
    void dom036_entityIdsAreUpperCaseOnly() {
        for (String ok : List.of("E2E-D-0001", "E2E-T-1", "E2E-T|PIPE", "OLD-PIPE-C|A", "OLD-C-1")) {
            assertThat(EntityId.of(ok).value()).isEqualTo(ok);
            assertThat(ConditionId.of(ok).value()).isEqualTo(ok);
        }
        for (String bad : List.of("e2e-d-0001", "E2E-t-1", "Abc", "", " ")) {
            Expect.invalid(() -> EntityId.of(bad));
            Expect.invalid(() -> TaskId.of(bad));
            Expect.invalid(() -> ConditionId.of(bad));
        }
    }
}
