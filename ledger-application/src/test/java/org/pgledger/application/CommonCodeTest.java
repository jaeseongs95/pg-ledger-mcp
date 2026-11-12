package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.pgledger.testing.TestOwners;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.application.write.SaveService;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.CodeValue;

/** SVC-CC-01 .. SVC-CC-10: the common codes. */
@LedgerServiceTest
class CommonCodeTest {

    private static CodeCommand code(String group, String code) {
        return new CodeCommand(group, code, "이름", "Name", null, null, 25, null);
    }

    private long revision(Scenario s) {
        return s.bean(CodeRevisionRepository.class).current();
    }

    static Stream<String> codeGroups() {
        return Stream.of("DEFECT_CATEGORY", "DEFECT_SEVERITY", "RELATION_KIND");
    }

    @ParameterizedTest
    @MethodSource("codeGroups")
    @DisplayName("SVC-CC-01: the three code groups are accepted")
    void cc01_validGroups(String group, Scenario s) {
        CodeCommand inactive = new CodeCommand(group, "CC01_" + s.code(), "이름", "Name", null, null, 25, "INACTIVE");
        assertThat(s.bean(CommonCodeService.class).set(inactive, TestOwners.OWNER).code().group().name()).isEqualTo(group);
    }

    @Test
    @DisplayName("SVC-CC-01: any other group is refused")
    void cc01_otherGroup(Scenario s) {
        Expect.failure(() -> s.bean(CommonCodeService.class).set(code("X", "CC01"), TestOwners.OWNER), ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-CC-02: a new code is active, bumps the revision and is usable at once")
    void cc02_newCode(Scenario s) {
        long before = revision(s);
        String category = "UI_" + s.code();
        var result = s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "화면", "UI",
                null, null, 25, null), TestOwners.OWNER);
        assertThat(result.code().state()).isEqualTo(CodeState.ACTIVE);
        assertThat(result.revision()).isEqualTo(before + 1);
        var fields = s.defectFields("d");
        fields.put("category", category);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields)).version())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-CC-03: an inactive code stays, is refused for new writes and kept by existing rows")
    void cc03_inactive(Scenario s) {
        String category = "OLD_" + s.code();
        s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "옛", "Old", null, null, 30,
                null), TestOwners.OWNER);
        var fields = s.defectFields("d");
        fields.put("category", category);
        var defect = s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields));
        s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", category, "옛", "Old", null, null, 30,
                "INACTIVE"), TestOwners.OWNER);
        assertThat(s.bean(CommonCodeRepository.class).find(CodeGroup.DEFECT_CATEGORY, CodeValue.of(category)))
                .isPresent();
        Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR, s.create("defect", null, fields)),
                ValidationCode.CODE_INVALID);
        assertThat(s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("defect", defect.id(), 1,
                Map.of("title", "kept"))).version()).isEqualTo(2);
        assertThat(s.defect(defect.id()).category().value()).isEqualTo(category);
    }

    @Test
    @DisplayName("SVC-CC-04: the same key updates text and state; the service has no delete and no rename")
    void cc04_neverDeleted(Scenario s) {
        String name = "CC04_" + s.code();
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        var updated = s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", name, "바뀜", "Changed",
                null, null, 26, null), TestOwners.OWNER);
        assertThat(updated.code().nameEn()).isEqualTo("Changed");
        assertThat(updated.code().createdBy().value()).isEqualTo("user:owner");
        assertThat(Arrays.stream(CommonCodeService.class.getMethods()).map(m -> m.getName()))
                .noneMatch(n -> n.matches("(?i).*(delete|remove|rename).*"));
        assertThat(Arrays.stream(CommonCodeRepository.class.getMethods()).map(m -> m.getName()))
                .noneMatch(n -> n.matches("(?i).*(delete|remove|rename).*"));
    }

    /** A code command with one field at, or outside, its limit. */
    record Limit(String name, CodeCommand command, ErrorCode expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static CodeCommand named(String code, String ko, String en, String descriptionKo) {
        return new CodeCommand("RELATION_KIND", code, ko, en, descriptionKo, null, 40, null);
    }

    static Stream<Limit> limits() {
        ErrorCode refused = ValidationCode.VALIDATION_FAILED;
        return Stream.of(
                new Limit("name_ko empty", named("LIM_A", "", "x", null), refused),
                new Limit("name_ko 1", named("LIM_B", "k", "x", null), null),
                new Limit("name_ko 200", named("LIM_C", "k".repeat(200), "x", null), null),
                new Limit("name_ko 201", named("LIM_D", "k".repeat(201), "x", null), refused),
                new Limit("description 500", named("LIM_E", "k", "x", "d".repeat(500)), null),
                new Limit("description 501", named("LIM_F", "k", "x", "d".repeat(501)), refused),
                new Limit("code with a leading digit", named("1LIM", "k", "x", null), refused),
                new Limit("code of 32 characters", named("L".repeat(32), "k", "x", null), null),
                new Limit("code of 33 characters", named("L".repeat(33), "k", "x", null), refused),
                new Limit("code with a dash", named("LIM-G", "k", "x", null), refused));
    }

    @ParameterizedTest
    @MethodSource("limits")
    @DisplayName("SVC-CC-05: text and code limits")
    void cc05_limits(Limit limit, Scenario s) {
        assertThat(Expect.outcome(() -> s.bean(CommonCodeService.class).set(limit.command(), TestOwners.OWNER)))
                .isEqualTo(Optional.ofNullable(limit.expected()));
    }

    @Test
    @DisplayName("SVC-CC-06: an omitted English text falls back to the Korean text")
    void cc06_englishFallback(Scenario s) {
        var result = s.bean(CommonCodeService.class).set(new CodeCommand("RELATION_KIND", "CC06_" + s.code(), "한글",
                null, "설명", null, 41, null), TestOwners.OWNER);
        assertThat(result.code().nameEn()).isEqualTo("한글");
        assertThat(result.code().descriptionEn()).isEqualTo("설명");
    }

    @Test
    @DisplayName("SVC-CC-07: setting a code to what it already is does not bump the revision")
    void cc07_noSpuriousRevision(Scenario s) {
        String name = "CC07_" + s.code();
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        long before = revision(s);
        s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
        assertThat(revision(s)).isEqualTo(before);
    }

    @Test
    @DisplayName("SVC-CC-08: a rolled back set leaves the revision as it was")
    void cc08_rollback(Scenario s) {
        long before = revision(s);
        String name = "CC08_" + s.code();
        assertThatThrownBy(() -> s.ctx().tx(() -> {
            s.bean(CommonCodeService.class).set(code("RELATION_KIND", name), TestOwners.OWNER);
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(revision(s)).isEqualTo(before);
        assertThat(s.bean(CommonCodeRepository.class).find(CodeGroup.RELATION_KIND, CodeValue.of(name))).isEmpty();
    }

    @Test
    @DisplayName("SVC-CC-09: a list is ordered by sort order and code and starts with the seed")
    void cc09_ordering(Scenario s) {
        List<CommonCode> severities = s.bean(CommonCodeService.class).list(CodeGroup.DEFECT_SEVERITY);
        assertThat(severities.stream().map(c -> c.code().value())).containsExactly("P1", "P2", "P3", "UNRATED");
        assertThat(severities.getLast().state()).isEqualTo(CodeState.INACTIVE);
        assertThat(s.bean(CommonCodeService.class).list(CodeGroup.RELATION_KIND).stream()
                .map(c -> c.code().value())).startsWith("same_site", "caused_by", "blocks", "same_family",
                "same_fix_unit", "regression_of");
    }

    @Test
    @DisplayName("SVC-CC-10: with no category a defect cannot be created and the message points at the codes")
    void cc10_noCategories() {
        LedgerTestRunners.withoutCategories(fresh -> {
            Scenario s = new Scenario(fresh);
            var fields = s.defectFields("d");
            fields.remove("category");
            LedgerException e = Expect.failure(() -> s.bean(SaveService.class).save(Scenario.AUTHOR,
                    s.create("defect", null, fields)), ValidationCode.CODE_REQUIRED);
            assertThat(e.details()).containsEntry("valid", List.of());
            assertThat(e.args()).containsExactly("defect.category", "", "DEFECT_CATEGORY");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1LEADING", "WITH SPACE"})
    @DisplayName("SVC-CC-05: a code that does not match the pattern is refused")
    void cc05_codePattern(String code, Scenario s) {
        Expect.failure(() -> s.bean(CommonCodeService.class).set(code("RELATION_KIND", code), TestOwners.OWNER),
                ValidationCode.VALIDATION_FAILED);
    }

    private static long auditRows(Scenario s, String subject) {
        return s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :subject").param("subject", subject)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("SET-023: addIfAbsent adds a new code with the revision bump and one audit row, and leaves a code that exists exactly as it is")
    void set023_addIfAbsentNeverOverwrites(Scenario s) {
        CommonCodeService service = s.bean(CommonCodeService.class);
        String name = "SEED_" + s.code();
        String subject = "DEFECT_CATEGORY/" + name;
        long before = revision(s);
        CodeCommand seed = new CodeCommand("DEFECT_CATEGORY", name, "시드", "Seed", "설명", "Description", 10, "ACTIVE");

        var added = service.addIfAbsent(seed, TestOwners.OWNER);

        assertThat(added).isPresent();
        assertThat(added.orElseThrow().revision()).isEqualTo(before + 1);
        assertThat(auditRows(s, subject)).isEqualTo(1);
        // the owner changes and retires the code; the seed command is repeated
        service.set(new CodeCommand("DEFECT_CATEGORY", name, "내 이름", "My name", "내 설명", "My description", 77, "INACTIVE"),
                TestOwners.OWNER);
        long afterOwner = revision(s);
        long auditAfterOwner = auditRows(s, subject);

        assertThat(service.addIfAbsent(seed, TestOwners.OWNER)).isEmpty();

        CommonCode kept = s.bean(CommonCodeRepository.class).find(CodeGroup.DEFECT_CATEGORY, CodeValue.of(name)).orElseThrow();
        assertThat(kept.nameKo()).isEqualTo("내 이름");
        assertThat(kept.sortOrder()).isEqualTo(77);
        assertThat(kept.state()).isEqualTo(CodeState.INACTIVE);
        assertThat(revision(s)).isEqualTo(afterOwner);
        assertThat(auditRows(s, subject)).isEqualTo(auditAfterOwner);
    }
}
