package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.support.Dbms;
import org.pgledger.application.support.DbmsAware;
import org.pgledger.application.support.H2ServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;

/** SVC-PRV-01 .. SVC-PRV-03: who created what. */
@H2ServiceTest
class ProvenanceTest implements DbmsAware {

    @Parameter
    Dbms dbms;

    @Override
    public Dbms dbms() {
        return dbms;
    }

    /** A table and the SQL that counts the rows of the scenario that carry the expected creator. */
    record Created(String name, String sql) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Created> createdRows() {
        return Stream.of(
                new Created("task", "SELECT COUNT(*) FROM task WHERE project_id = ? AND created_by = ?"),
                new Created("defect", "SELECT COUNT(*) FROM defect WHERE project_id = ? AND created_by = ?"),
                new Created("condition", "SELECT COUNT(*) FROM release_condition WHERE project_id = ? "
                        + "AND created_by = ?"),
                new Created("defect_task", "SELECT COUNT(*) FROM defect_task WHERE task_id IN "
                        + "(SELECT id FROM task WHERE project_id = ?) AND created_by = ?"),
                new Created("defect_relation", "SELECT COUNT(*) FROM defect_relation WHERE a IN "
                        + "(SELECT id FROM defect WHERE project_id = ?) AND created_by = ?"),
                new Created("condition_link", "SELECT COUNT(*) FROM condition_link WHERE condition_id IN "
                        + "(SELECT id FROM release_condition WHERE project_id = ?) AND created_by = ?"));
    }

    @ParameterizedTest
    @MethodSource("createdRows")
    @DisplayName("SVC-PRV-01: a batch of the first host author stamps every row it creates with that author")
    void prv01_batchProvenance(Created created, Scenario s) {
        SaveResult d1 = s.newDefect("seed one");
        s.bean(BatchService.class).batch(Scenario.AUTHOR, null, List.of(
                new BatchOpInput.Save("task", s.taskFields("t"), Scenario.REASON, s.taskId(1), null, null),
                new BatchOpInput.Save("condition", s.conditionFields("r", "c"), Scenario.REASON, s.conditionId("1"),
                        null, null),
                new BatchOpInput.Save("defect", s.defectFields("second"), Scenario.REASON, null, null, null),
                new BatchOpInput.Link("defect_task", d1.id(), s.taskId(1), Scenario.REASON, null, null),
                new BatchOpInput.Link("blocks", d1.id(), s.defectId(2), Scenario.REASON, null, null),
                new BatchOpInput.Link("condition_task", s.conditionId("1"), s.taskId(1), Scenario.REASON, null,
                        null)));
        assertThat(s.count(created.sql(), s.projectId(), Scenario.AUTHOR)).isPositive();
        assertThat(s.count(created.sql().replace("created_by = ?", "created_by <> ?"), s.projectId(),
                Scenario.AUTHOR)).isZero();
    }

    @Test
    @DisplayName("SVC-PRV-02: a project and a code added by the owner operations are stamped user:owner")
    void prv02_ownerOperations(Scenario s) {
        s.bean(CommonCodeService.class).set(new CodeCommand("DEFECT_CATEGORY", "OWNED" + s.code(), "owned", null, null,
                null, 70, null));
        assertThat(s.count("SELECT COUNT(*) FROM project WHERE id = ? AND created_by = 'user:owner'",
                s.projectId())).isEqualTo(1);
        assertThat(s.count("SELECT COUNT(*) FROM common_code WHERE code = ? AND created_by = 'user:owner'",
                "OWNED" + s.code())).isEqualTo(1);
    }

    @Test
    @DisplayName("SVC-PRV-03: an edit by another author leaves created_by alone")
    void prv03_editKeepsTheCreator(Scenario s) {
        SaveResult d = s.newDefect("d");
        s.bean(SaveService.class).save(Scenario.DEFECT_ONLY, s.edit("defect", d.id(), 1, Map.of("title", "x")));
        assertThat(s.defect(d.id()).createdBy().value()).isEqualTo(Scenario.AUTHOR);
    }
}
