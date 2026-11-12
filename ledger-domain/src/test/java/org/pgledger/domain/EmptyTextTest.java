package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.Condition;
import org.pgledger.domain.model.ConditionFields;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.model.DefectFields;
import org.pgledger.domain.model.Task;
import org.pgledger.domain.model.TaskFields;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.TaskId;
import org.pgledger.domain.value.TextRule;

/**
 * REH3-TXT-01 .. 04 at the level of the domain: {@code purpose}, {@code success_condition}, {@code behavior},
 * {@code root_cause} and {@code condition_text} were NOT NULL columns that allowed {@code ''} in 1.x. In 2.0 they follow the
 * {@code save()} of 1.x: the field has to be given, and may be empty; an empty text is the value NULL in the row (no column
 * holds {@code ''}), and the lengths of the code of a condition (200) and of a CI reference (2000) hold the data of 1.x.
 */
class EmptyTextTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void reh3Txt01_aTaskMayHaveEmptyPurposeAndSuccessCondition() {
        TaskFields fields = TaskFields.of(Patch.set("p1"), Patch.set("title"), Patch.set(""), Patch.set("   "),
                Patch.absent(), Patch.absent(), Patch.absent());

        Task task = Task.plan(TaskId.of("AGS:A07-a"), fields, NOW);

        assertThat(task.purpose()).isNull();
        assertThat(task.successCondition()).isNull();
        Task edited = task.edited(TaskFields.of(Patch.absent(), Patch.absent(), Patch.set("a purpose"), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent()), NOW);
        assertThat(edited.purpose()).isEqualTo("a purpose");
        assertThat(edited.edited(TaskFields.of(Patch.absent(), Patch.absent(), Patch.set(""), Patch.absent(),
                Patch.absent(), Patch.absent(), Patch.absent()), NOW).purpose()).isNull();
    }

    @Test
    void reh3Txt02_theFieldHasToBeGiven() {
        // null is not empty: the column had no default in 1.x
        Expect.invalid(() -> TaskFields.of(Patch.set("p1"), Patch.set("title"), Patch.set(null), Patch.set("s"),
                Patch.absent(), Patch.absent(), Patch.absent()));
        TaskFields withoutPurpose = TaskFields.of(Patch.set("p1"), Patch.set("title"), Patch.absent(), Patch.set("s"),
                Patch.absent(), Patch.absent(), Patch.absent());
        Expect.invalid(withoutPurpose::requireCreatable);
        Expect.invalid(() -> DefectFields.of(Patch.set("p1"), Patch.set("t"), Patch.set("ARC"), Patch.set("P2"),
                Patch.absent(), Patch.set("c"), Patch.absent()).requireCreatable());
        Expect.invalid(() -> ConditionFields.of(Patch.set("p1"), Patch.set("1.0"), Patch.absent(), Patch.set("C1"),
                Patch.absent()).requireCreatable());
        // a title and a release are still required to have text
        Expect.invalid(() -> TaskFields.of(Patch.set("p1"), Patch.set(""), Patch.set("p"), Patch.set("s"),
                Patch.absent(), Patch.absent(), Patch.absent()));
        Expect.invalid(() -> ConditionFields.of(Patch.set("p1"), Patch.set(""), Patch.absent(), Patch.set("C1"),
                Patch.set("t")));
    }

    @Test
    void reh3Txt04_aDefectAndAConditionMayHaveEmptyText() {
        DefectFields defect = DefectFields.of(Patch.set("p1"), Patch.set("t"), Patch.set("ARC"), Patch.set("P2"),
                Patch.set(""), Patch.set(" "), Patch.absent());
        Defect row = Defect.open(DefectId.of("AGS-D-0001"), defect, NOW);
        assertThat(row.behavior()).isNull();
        assertThat(row.rootCause()).isNull();

        ConditionFields condition = ConditionFields.of(Patch.set("p1"), Patch.set("1.0"), Patch.absent(),
                Patch.set("C1"), Patch.set(""));
        Condition open = Condition.open(ConditionId.of("ags:c1"), condition);
        assertThat(open.conditionText()).isNull();
    }

    @Test
    void reh3Len02_theOriginReferenceHoldsWhatOneXHeld() {
        assertThat(TextRule.ORIGIN_REF.max()).isEqualTo(20000);
        assertThat(TextRule.ORIGIN_REF.optional("o".repeat(9408))).hasSize(9408);
        assertThat(TextRule.ORIGIN_REF.optional("o".repeat(20000))).hasSize(20000);
        Expect.invalid(() -> TextRule.ORIGIN_REF.optional("o".repeat(20001)));
    }

    @Test
    void reh3Len01_theLimitsHoldTheDataOfOneX() {
        assertThat(TextRule.CONDITION_CODE.max()).isEqualTo(200);
        assertThat(TextRule.CI_REF.max()).isEqualTo(2000);
        assertThat(TextRule.CONDITION_CODE.required("c".repeat(200))).hasSize(200);
        assertThat(TextRule.CI_REF.optional("r".repeat(2000))).hasSize(2000);
        Expect.invalid(() -> TextRule.CONDITION_CODE.required("c".repeat(201)));
        Expect.invalid(() -> TextRule.CI_REF.optional("r".repeat(2001)));
        ValidationException refused = (ValidationException) Expect.invalid(
                () -> TextRule.CI_REF.optional("r".repeat(2001)));
        assertThat(refused.issues().getFirst().reasonArgs()).containsExactly("2000");
    }
}
