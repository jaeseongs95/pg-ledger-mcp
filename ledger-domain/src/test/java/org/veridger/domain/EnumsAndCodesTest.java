// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.veridger.domain.error.ValidationCode;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.model.CodeCatalog;
import org.veridger.domain.model.CommonCode;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.CheckResult;
import org.veridger.domain.rule.CodeDirection;
import org.veridger.domain.rule.CodeGroup;
import org.veridger.domain.rule.CodeState;
import org.veridger.domain.rule.CriterionState;
import org.veridger.domain.rule.DeliverableTarget;
import org.veridger.domain.rule.IssueStatus;
import org.veridger.domain.rule.LinkKind;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.rule.SpecStatus;
import org.veridger.domain.rule.TaskState;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CodeValue;
import org.veridger.domain.value.Tokens;

/** DOM-020 .. DOM-022: closed sets and the common-code policy. */
class EnumsAndCodesTest {

    private static <E extends Enum<E>> Set<String> names(Class<E> type) {
        return Set.copyOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }

    @Test
    void dom020_enumsCoverAllUniversalLedgerStates() {
        assertThat(names(IssueStatus.class)).containsExactlyInAnyOrder("OPEN", "RESOLVED", "VERIFIED",
                "NOT_AN_ISSUE", "DISPUTED", "MERGED");
        assertThat(names(SpecStatus.class)).containsExactlyInAnyOrder("DRAFT", "PLANNED", "ACTIVE", "BLOCKED",
                "COMPLETED", "VERIFIED", "CANCELLED");
        assertThat(names(TaskState.class)).containsExactlyInAnyOrder("UNKNOWN", "PLANNED", "ACTIVE", "BLOCKED",
                "COMPLETED", "VERIFIED", "CANCELLED");
        assertThat(names(CriterionState.class)).containsExactlyInAnyOrder("UNKNOWN", "PLANNED", "ACTIVE",
                "COMPLETED", "MET", "NOT_MET");
        assertThat(Scope.assignable()).extracting(Scope::token).containsExactly(
                "issue_create", "issue_update", "spec_create", "spec_update", "task_create", "task_update",
                "criterion_create", "criterion_update", "deliverable_create", "deliverable_update",
                "version_create", "version_update", "verify");
        assertThat(names(Channel.class)).containsExactlyInAnyOrder("AUTHOR", "REVIEWER", "ADMIN");
        assertThat(Tokens.allowed(CheckResult.values())).containsExactly("passed", "failed", "cancelled");
        assertThat(Tokens.allowed(DeliverableTarget.values())).containsExactly("issue", "spec", "task");
        assertThat(Tokens.allowed(CodeState.values())).containsExactly("ACTIVE", "INACTIVE");
        assertThat(Tokens.allowed(CodeGroup.values())).containsExactly("ISSUE_CATEGORY", "ISSUE_SEVERITY",
                "DELIVERABLE_KIND", "RELATION_KIND");
        assertThat(Tokens.parse("scope", "verify", Scope.values())).isEqualTo(Scope.VERIFY);
        assertThat(Tokens.parse("check_result", "passed", CheckResult.values())).isEqualTo(CheckResult.PASSED);
    }

    @Test
    void dom020_unknownTokensFailWithTheAllowedList() {
        ValidationException e = org.assertj.core.api.Assertions.catchThrowableOfType(ValidationException.class,
                () -> Tokens.parse("scope", "FIXED", Scope.values()));
        assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().getFirst().allowed()).containsExactlyElementsOf(Tokens.allowed(Scope.values()));
        Expect.invalid(() -> Tokens.parse("check_result", "unknown", CheckResult.values()));
        Expect.invalid(() -> Tokens.parse("scope", null, Scope.values()));
        // structural link kinds vs common-code relation kinds
        assertThat(LinkKind.ofToken("issue_task")).isEqualTo(LinkKind.ISSUE_TASK);
        assertThat(LinkKind.ofToken("issue_spec")).isEqualTo(LinkKind.ISSUE_SPEC);
        assertThat(LinkKind.ofToken("spec_task")).isEqualTo(LinkKind.SPEC_TASK);
        assertThat(LinkKind.ofToken("criterion_issue")).isEqualTo(LinkKind.CRITERION_ISSUE);
        assertThat(LinkKind.ofToken("criterion_spec")).isEqualTo(LinkKind.CRITERION_SPEC);
        assertThat(LinkKind.ofToken("related")).isEqualTo(LinkKind.RELATION);
    }

    private static CommonCode code(CodeGroup group, String code, int order, CodeState state) {
        return new CommonCode(group, CodeValue.of(code), code, code, null, null, order, state, null, AuthorId.OWNER);
    }

    private static CommonCode relationKind(String code, int order, CodeState state, CodeDirection direction) {
        return new CommonCode(CodeGroup.RELATION_KIND, CodeValue.of(code), code, code, null, null, order, state,
                direction, AuthorId.OWNER);
    }

    @Test
    void ldir01_theDirectionIsAPropertyOfTheRelationKindCode() {
        CodeCatalog kinds = new CodeCatalog(CodeGroup.RELATION_KIND, List.of(
                relationKind("caused_by", 2, CodeState.ACTIVE, CodeDirection.GIVEN),
                relationKind("same_site", 1, CodeState.ACTIVE, null),
                relationKind("retired_directed", 3, CodeState.INACTIVE, CodeDirection.GIVEN)));
        assertThat(kinds.directionOf(CodeValue.of("caused_by"))).contains(CodeDirection.GIVEN);
        assertThat(kinds.directionOf(CodeValue.of("same_site"))).as("no direction is the canonical order")
                .contains(CodeDirection.CANONICAL);
        assertThat(kinds.directionOf(CodeValue.of("retired_directed"))).as("a retired kind keeps its direction")
                .contains(CodeDirection.GIVEN);
        assertThat(kinds.directionOf(CodeValue.of("unknown_kind"))).isEmpty();
        assertThat(Tokens.allowed(CodeDirection.values())).containsExactly("GIVEN", "CANONICAL");
        Expect.invalid(() -> new CommonCode(CodeGroup.ISSUE_CATEGORY, CodeValue.of("UI"), "ui", null, null, null, 1,
                CodeState.ACTIVE, CodeDirection.GIVEN, AuthorId.OWNER));
        assertThat(new CommonCode(CodeGroup.ISSUE_CATEGORY, CodeValue.of("UI"), "ui", null, null, null, 1,
                CodeState.ACTIVE, CodeDirection.CANONICAL, AuthorId.OWNER).direction())
                .isEqualTo(CodeDirection.CANONICAL);
    }

    private static CodeCatalog severity() {
        return new CodeCatalog(CodeGroup.ISSUE_SEVERITY, List.of(code(CodeGroup.ISSUE_SEVERITY, "P3", 3,
                CodeState.ACTIVE), code(CodeGroup.ISSUE_SEVERITY, "P1", 1, CodeState.ACTIVE),
                code(CodeGroup.ISSUE_SEVERITY, "P2", 2, CodeState.ACTIVE),
                code(CodeGroup.ISSUE_SEVERITY, "UNRATED", 9, CodeState.INACTIVE)));
    }

    private static CodeCatalog category() {
        return new CodeCatalog(CodeGroup.ISSUE_CATEGORY, List.of(code(CodeGroup.ISSUE_CATEGORY, "PROCESS", 0,
                CodeState.ACTIVE), code(CodeGroup.ISSUE_CATEGORY, "RETIRED", 0, CodeState.INACTIVE)));
    }

    @Test
    void dom021_codePolicy() {
        CodeValue p2 = CodeValue.of("P2");
        Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("issue.severity", CodeValue.of("P9"), null));
        Expect.failsWith(ValidationCode.CODE_REQUIRED, () -> severity().check("issue.severity", null, null));
        Expect.failsWith(ValidationCode.CODE_REQUIRED, () -> category().check("issue.category", null, null));
        severity().check("issue.severity", p2, null);
        Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> category().check("issue.category", CodeValue.of("RETIRED"), null));
        Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> category().check("issue.category", CodeValue.of("RETIRED"), CodeValue.of("PROCESS")));
        category().check("issue.category", CodeValue.of("RETIRED"), CodeValue.of("RETIRED"));
        var retired = Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("issue.severity", CodeValue.of("UNRATED"), CodeValue.of("UNRATED")));
        assertThat(retired.details().get("holdsRetiredValue")).containsExactly("true");
        var notRetired = Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("issue.severity", CodeValue.of("P9"), null));
        assertThat(notRetired.details().get("holdsRetiredValue")).containsExactly("false");
    }

    @Test
    void dom022_validListHoldsActiveCodesInSortOrder() {
        assertThat(severity().validCodes()).extracting(CodeValue::value).containsExactly("P1", "P2", "P3");
        var failure = Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("issue.severity", CodeValue.of("UNRATED"), null));
        assertThat(failure.details().get("valid")).containsExactly("P1", "P2", "P3");
        CodeCatalog tied = new CodeCatalog(CodeGroup.ISSUE_CATEGORY, List.of(
                code(CodeGroup.ISSUE_CATEGORY, "ZED", 1, CodeState.ACTIVE),
                code(CodeGroup.ISSUE_CATEGORY, "ALPHA", 1, CodeState.ACTIVE)));
        assertThat(tied.validCodes()).extracting(CodeValue::value).containsExactly("ALPHA", "ZED");
    }
}
