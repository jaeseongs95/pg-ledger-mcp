package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.model.ConditionFields;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.DefectFix;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.rule.CiConclusion;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CanonicalPayload;
import org.pgledger.domain.value.CanonicalPayload.Fields;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.CommitSha;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Evidence;
import org.pgledger.domain.value.MaxRows;
import org.pgledger.domain.value.PageCursor;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.Sha256;
import org.pgledger.domain.value.TextRule;
import org.pgledger.domain.value.UtcInstant;

/** DOM-028 .. DOM-034: digests, limits, field sets, cursors, row limits, text normalisation and UTC time. */
class ValuesTest {

    @Test
    void dom028_payloadDigestIgnoresOrderAndNullsAndSeesEveryValue() {
        Fields a = Fields.empty().text("id", "E2E-D-0001").text("reason", "because of the reason")
                .with("fields", Fields.empty().text("title", "t").text("severity", "P1"));
        Fields b = Fields.empty()
                .with("fields", Fields.empty().text("severity", "P1").text("title", "t"))
                .text("reason", "because of the reason").text("id", "E2E-D-0001");
        assertThat(CanonicalPayload.digest("save", a)).isEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("save", a)).isEqualTo(CanonicalPayload.digest("save", b));
        // an optional field that is null equals one that is absent
        assertThat(CanonicalPayload.digest("save", a.text("branch", null))).isEqualTo(CanonicalPayload.digest("save", a));
        // every value and the operation matter
        assertThat(CanonicalPayload.digest("save", a.text("reason", "another reason entirely")))
                .isNotEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("transition", a)).isNotEqualTo(CanonicalPayload.digest("save", a));
        assertThat(CanonicalPayload.digest("save", a.with("fields", Fields.empty().text("title", "t2"))))
                .isNotEqualTo(CanonicalPayload.digest("save", a));
        // values that would run together do not collide
        assertThat(CanonicalPayload.digest("x", Fields.empty().text("ab", "c")))
                .isNotEqualTo(CanonicalPayload.digest("x", Fields.empty().text("a", "bc")));
        // golden value: stable across JVMs
        assertThat(CanonicalPayload.digest("save", Fields.empty().text("id", "E2E-D-0001").text("v", "1")).hex())
                .isEqualTo(GOLDEN);
    }

    /** sha256 of {@code t4:save{k2:idt10:E2E-D-0001k1:vt1:1}}, computed independently with sha256sum. */
    private static final String GOLDEN = "700bad380a8773163a485424a6869901846cf6df8a7bd73b9bd477b2dad8f8df";

    @Test
    void dom029_limitsAtTheBoundaries() {
        for (TextRule rule : new TextRule[] {TextRule.TITLE, TextRule.BEHAVIOR, TextRule.ROOT_CAUSE, TextRule.PURPOSE,
                TextRule.SUCCESS_CONDITION, TextRule.ORIGIN_QUOTE, TextRule.CONDITION_TEXT, TextRule.OBSERVATION,
                TextRule.EVIDENCE, TextRule.USER_QUOTE, TextRule.GRANT_NOTE}) {
            assertThat(rule.optional("x".repeat(rule.max()))).hasSize(rule.max());
            assertThat(rule.optional("가".repeat(rule.max()))).hasSize(rule.max());
            Expect.invalid(() -> rule.optional("x".repeat(rule.max() + 1)));
        }
        assertThat(TextRule.TITLE.max()).isEqualTo(300);
        assertThat(TextRule.BEHAVIOR.max()).isEqualTo(4000);
        assertThat(TextRule.ORIGIN_QUOTE.max()).isEqualTo(2000);
        assertThat(TextRule.EVIDENCE.max()).isEqualTo(2000);
        assertThat(TextRule.GRANT_NOTE.max()).isEqualTo(500);
        Expect.invalid(() -> TextRule.TITLE.required(""));
        assertThat(TextRule.CI_REF.optional("   ")).isNull();
        assertThat(TextRule.CI_REF.optional(" run-1 ")).isEqualTo("run-1");
        // commit and evidence digests
        assertThat(CommitSha.of("a".repeat(40)).value()).hasSize(40);
        for (String bad : List.of("a".repeat(39), "a".repeat(41), "A".repeat(40))) {
            Expect.invalid(() -> CommitSha.of(bad));
        }
        assertThat(Sha256.of("b".repeat(64)).hex()).hasSize(64);
        for (String bad : List.of("b".repeat(63), "b".repeat(65), "B".repeat(64))) {
            Expect.invalid(() -> Sha256.of(bad));
        }
        Expect.invalid(() -> Evidence.of("https://x", "b".repeat(63)));
        // a fix with a conclusion needs a reference
        Expect.invalid(() -> DefectFix.record(1, DefectId.of("E2E-D-0001"), "repo", CommitSha.of("a".repeat(40)),
                null, "  ", CiConclusion.SUCCESS, null, AuthorId.OWNER, DomainFixtures.NOW));
    }

    private static Patch<String> set(String v) {
        return Patch.set(v);
    }

    private static Patch<String> none() {
        return Patch.absent();
    }

    @Test
    void dom030_createNeedsRequiredFieldsAndEditNeedsAtLeastOne() {
        DefectFields complete = DefectFields.of(set("e2e"), set("title"), set("ARC"), set("P2"), set("behavior"),
                set("cause"), none());
        Defect created = Defect.open(DefectId.of("E2E-D-0001"), complete, DomainFixtures.NOW);
        assertThat(created.version()).isZero();
        assertThat(created.caseSeq()).isZero();
        assertThat(created.status().name()).isEqualTo("OPEN");
        // every required field is needed on create (code columns are checked by the code catalogue)
        for (DefectFields missing : List.of(
                DefectFields.of(none(), set("t"), set("A"), set("P1"), set("b"), set("c"), none()),
                DefectFields.of(set("e2e"), none(), set("A"), set("P1"), set("b"), set("c"), none()),
                DefectFields.of(set("e2e"), set("t"), set("A"), set("P1"), none(), set("c"), none()),
                DefectFields.of(set("e2e"), set("t"), set("A"), set("P1"), set("b"), none(), none()))) {
            Expect.invalid(missing::requireCreatable);
        }
        // edit: any non-empty subset, an empty set is rejected
        DefectFields empty = DefectFields.of(none(), none(), none(), none(), none(), none(), none());
        Expect.invalid(empty::requireEditable);
        DefectFields titleOnly = DefectFields.of(none(), set("new"), none(), none(), none(), none(), none());
        assertThat(titleOnly.requireEditable()).isSameAs(titleOnly);
        Defect edited = created.edited(titleOnly, DomainFixtures.NOW.plusSeconds(1));
        assertThat(edited.title()).isEqualTo("new");
        assertThat(edited.behavior()).isEqualTo("behavior");
        assertThat(edited.updatedAt()).isEqualTo(DomainFixtures.NOW.plusSeconds(1));
        // setting an optional field to nothing clears it; a required field cannot be cleared
        DefectFields clearOwner = DefectFields.of(none(), none(), none(), none(), none(), none(), set(""));
        assertThat(created.edited(clearOwner, DomainFixtures.NOW).ownerActorId()).isNull();
        Expect.invalid(() -> DefectFields.of(none(), set("  "), none(), none(), none(), none(), none()));
        Expect.failsWith(TrustCode.AUTHOR_ID_FORMAT,
                () -> DefectFields.of(none(), none(), none(), none(), none(), none(), set("nobody")));

        TaskFields task = TaskFields.of(set("e2e"), set("t"), set("p"), set("s"), none(), set(""), none());
        assertThat(task.requireCreatable().originRef().orElse("unset")).isNull();
        Expect.invalid(() -> TaskFields.of(set("e2e"), set("t"), none(), set("s"), none(), none(), none())
                .requireCreatable());
        ConditionFields condition = ConditionFields.of(set("e2e"), set("1.0"), none(), set("C1"), set("text"));
        assertThat(condition.requireCreatable()).isSameAs(condition);
        Expect.invalid(() -> ConditionFields.of(none(), none(), none(), none(), none()).requireEditable());
        // the editable sets are exactly the old ones: no state, version, id or audit field exists to set
        assertThat(DefectFields.class.getRecordComponents()).extracting("name").containsExactly("projectId",
                "title", "category", "severity", "behavior", "rootCause", "ownerActorId");
        assertThat(TaskFields.class.getRecordComponents()).extracting("name").containsExactly("projectId", "title",
                "purpose", "successCondition", "ownerActorId", "originRef", "originQuote");
        assertThat(ConditionFields.class.getRecordComponents()).extracting("name").containsExactly("projectId",
                "release", "goalLabel", "code", "conditionText");
    }

    @Test
    void dom031_cursorsRoundTripAndDetectDamage() {
        PageCursor cursor = new PageCursor(PageCursor.Kind.DEFECTS, "2026-11-05T10:00:00Z", "E2E-D-0042");
        String token = cursor.encode();
        assertThat(PageCursor.decode(token, PageCursor.Kind.DEFECTS)).isEqualTo(cursor);
        assertThat(token).doesNotContain("E2E-D").doesNotContain("DEFECTS");
        PageCursor noKey = new PageCursor(PageCursor.Kind.HISTORY, null, "17");
        assertThat(PageCursor.decode(noKey.encode(), PageCursor.Kind.HISTORY)).isEqualTo(noKey);
        // another list, garbage, truncation and tampering are all rejected
        Expect.invalid(() -> PageCursor.decode(token, PageCursor.Kind.TASKS));
        Expect.invalid(() -> PageCursor.decode("%%%not-base64%%%", PageCursor.Kind.DEFECTS));
        Expect.invalid(() -> PageCursor.decode(token.substring(0, token.length() - 3), PageCursor.Kind.DEFECTS));
        Expect.invalid(() -> PageCursor.decode(token.substring(0, 5) + "x" + token.substring(6),
                PageCursor.Kind.DEFECTS));
        Expect.invalid(() -> PageCursor.decode("", PageCursor.Kind.DEFECTS));
        Expect.invalid(() -> PageCursor.decode(null, PageCursor.Kind.DEFECTS));
        Expect.invalid(() -> new PageCursor(PageCursor.Kind.DEFECTS, "a\nb", "x"));
    }

    @Test
    void dom032_maxRows() {
        assertThat(MaxRows.of(1).value()).isEqualTo(1);
        assertThat(MaxRows.of(1000).value()).isEqualTo(1000);
        assertThat(MaxRows.of(null)).isEqualTo(MaxRows.DEFAULT);
        assertThat(MaxRows.DEFAULT.value()).isEqualTo(50);
        for (Number bad : new Number[] {0, -1, 1001, 50.5}) {
            Expect.invalid(() -> MaxRows.of(bad));
        }
        assertThat(MaxRows.of(50.0).value()).isEqualTo(50);
    }

    @Test
    void dom033_textNormalisation() {
        for (String blank : new String[] {"", "   ", null}) {
            assertThat(TextRule.USER_QUOTE.optional(blank)).isNull();
            Expect.invalid(() -> TextRule.TITLE.required(blank));
        }
        assertThat(TextRule.TITLE.optional(" x ")).isEqualTo(" x ");
        assertThat(TextRule.EVIDENCE.optional(" x ")).isEqualTo("x");
        assertThat(Evidence.of("  ", null).provided()).isFalse();
        assertThat(Evidence.of("", null)).isEqualTo(Evidence.NONE);
        CommonCode code = new CommonCode(CodeGroup.DEFECT_CATEGORY, CodeValue.of("UI_UX"), "화면", null, "설명", " ",
                25, CodeState.ACTIVE, AuthorId.OWNER);
        assertThat(code.nameEn()).isEqualTo("화면");
        assertThat(code.descriptionEn()).isEqualTo("설명");
        assertThat(ValidationCode.VALIDATION_FAILED.token()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void dom034_timeInputNeedsAZoneAndOutputEndsInZ() {
        UtcInstant z = UtcInstant.parse("t", "2026-11-05T10:00:00Z");
        UtcInstant offset = UtcInstant.parse("t", "2026-11-05T19:00:00+09:00");
        assertThat(offset).isEqualTo(z);
        assertThat(z.toString()).isEqualTo("2026-11-05T10:00:00Z").endsWith("Z");
        assertThat(offset.toString()).endsWith("Z");
        assertThat(UtcInstant.parse("t", "2026-11-05T10:00:00.123456Z").toString()).endsWith("Z");
        for (String bad : new String[] {"2026-11-05T10:00:00", "2026-11-05", "", "yesterday", null}) {
            Expect.invalid(() -> UtcInstant.parse("observed_at", bad));
        }
    }
}
