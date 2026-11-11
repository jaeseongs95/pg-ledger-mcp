package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.CodeCatalog;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.LinkKind;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.Tokens;

/** DOM-020 .. DOM-022: closed sets and the common-code policy. */
class EnumsAndCodesTest {

    private static <E extends Enum<E>> Set<String> names(Class<E> type) {
        return Set.copyOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }

    @Test
    void dom020_enumsEqualTheOldCheckSets() {
        assertThat(names(DefectStatus.class)).containsExactlyInAnyOrder("OPEN", "SOURCE_FIXED", "VERIFIED",
                "NOT_DEFECT", "DISPUTED", "MERGED");
        assertThat(names(TaskState.class)).containsExactlyInAnyOrder("UNKNOWN", "PLANNED", "ACTIVE", "BLOCKED",
                "REPORTED_DONE", "VERIFIED", "CANCELLED");
        assertThat(names(ConditionState.class)).containsExactlyInAnyOrder("UNKNOWN", "MET", "NOT_MET");
        assertThat(Tokens.allowed(Scope.values())).containsExactly("defect", "task", "fix", "verify");
        assertThat(names(Channel.class)).containsExactlyInAnyOrder("AUTHOR", "REVIEWER");
        assertThat(Tokens.allowed(CiConclusion.values())).containsExactly("success", "failure", "cancelled");
        assertThat(Tokens.allowed(CodeState.values())).containsExactly("ACTIVE", "INACTIVE");
        assertThat(Tokens.allowed(CodeGroup.values())).containsExactly("DEFECT_CATEGORY", "DEFECT_SEVERITY",
                "RELATION_KIND");
        assertThat(Tokens.parse("scope", "verify", Scope.values())).isEqualTo(Scope.VERIFY);
        assertThat(Tokens.parse("ci_conclusion", "success", CiConclusion.values())).isEqualTo(CiConclusion.SUCCESS);
    }

    @Test
    void dom020_unknownTokensFailWithTheAllowedList() {
        ValidationException e = org.assertj.core.api.Assertions.catchThrowableOfType(ValidationException.class,
                () -> Tokens.parse("scope", "FIXED", Scope.values()));
        assertThat(e.code()).isEqualTo(ValidationCode.VALIDATION_FAILED);
        assertThat(e.issues().getFirst().allowed()).containsExactly("defect", "task", "fix", "verify");
        Expect.invalid(() -> Tokens.parse("ci_conclusion", "passed", CiConclusion.values()));
        Expect.invalid(() -> Tokens.parse("scope", null, Scope.values()));
        // any token that is not structural names a relation kind, which is data (checked against the common codes)
        assertThat(LinkKind.ofToken("defect_task")).isEqualTo(LinkKind.DEFECT_TASK);
        assertThat(LinkKind.ofToken("condition_defect")).isEqualTo(LinkKind.CONDITION_DEFECT);
        assertThat(LinkKind.ofToken("related")).isEqualTo(LinkKind.RELATION);
    }

    private static CommonCode code(CodeGroup group, String code, int order, CodeState state) {
        return new CommonCode(group, CodeValue.of(code), code, code, null, null, order, state, AuthorId.OWNER);
    }

    private static CodeCatalog severity() {
        return new CodeCatalog(CodeGroup.DEFECT_SEVERITY, List.of(code(CodeGroup.DEFECT_SEVERITY, "P3", 3,
                CodeState.ACTIVE), code(CodeGroup.DEFECT_SEVERITY, "P1", 1, CodeState.ACTIVE),
                code(CodeGroup.DEFECT_SEVERITY, "P2", 2, CodeState.ACTIVE),
                code(CodeGroup.DEFECT_SEVERITY, "UNRATED", 9, CodeState.INACTIVE)));
    }

    private static CodeCatalog category() {
        return new CodeCatalog(CodeGroup.DEFECT_CATEGORY, List.of(code(CodeGroup.DEFECT_CATEGORY, "ARC", 0,
                CodeState.ACTIVE), code(CodeGroup.DEFECT_CATEGORY, "RETIRED", 0, CodeState.INACTIVE)));
    }

    @Test
    void dom021_codePolicy() {
        CodeValue p2 = CodeValue.of("P2");
        // unknown and missing
        Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("defect.severity", CodeValue.of("P9"), null));
        Expect.failsWith(ValidationCode.CODE_REQUIRED, () -> severity().check("defect.severity", null, null));
        Expect.failsWith(ValidationCode.CODE_REQUIRED, () -> category().check("defect.category", null, null));
        // active
        severity().check("defect.severity", p2, null);
        // inactive: refused on insert and when changed
        Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> category().check("defect.category", CodeValue.of("RETIRED"), null));
        Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> category().check("defect.category", CodeValue.of("RETIRED"), CodeValue.of("ARC")));
        // retained when unchanged on a column that is not always-active
        category().check("defect.category", CodeValue.of("RETIRED"), CodeValue.of("RETIRED"));
        // always-active: refused on every write, and the details flag the legacy value
        var legacy = Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("defect.severity", CodeValue.of("UNRATED"), CodeValue.of("UNRATED")));
        assertThat(legacy.details().get("holdsLegacyValue")).containsExactly("true");
        var notLegacy = Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("defect.severity", CodeValue.of("P9"), null));
        assertThat(notLegacy.details().get("holdsLegacyValue")).containsExactly("false");
    }

    @Test
    void dom022_validListHoldsActiveCodesInSortOrder() {
        assertThat(severity().validCodes()).extracting(CodeValue::value).containsExactly("P1", "P2", "P3");
        var failure = Expect.failsWith(ValidationCode.CODE_INVALID,
                () -> severity().check("defect.severity", CodeValue.of("UNRATED"), null));
        assertThat(failure.details().get("valid")).containsExactly("P1", "P2", "P3");
        // equal sort order: ordered by code
        CodeCatalog tied = new CodeCatalog(CodeGroup.DEFECT_CATEGORY, List.of(
                code(CodeGroup.DEFECT_CATEGORY, "ZED", 1, CodeState.ACTIVE),
                code(CodeGroup.DEFECT_CATEGORY, "ALPHA", 1, CodeState.ACTIVE)));
        assertThat(tied.validCodes()).extracting(CodeValue::value).containsExactly("ALPHA", "ZED");
    }
}
