package org.pgledger.database.repository;

import java.util.List;
import org.pgledger.domain.model.ConditionLink;
import org.pgledger.domain.model.DefectRelation;
import org.pgledger.domain.model.DefectTaskLink;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.TaskId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The three link tables. Links are the only rows that are deleted (an UNLINK; the history keeps the story). An
 * add is one statement that inserts only when the parent exists and the link does not, and reports whether a row
 * was written; two adds of the same link at the same moment still collide on the key (a duplicate-key exception).
 */
@Repository
public class LinkRepository {

    private final JdbcClient jdbc;

    public LinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean add(DefectTaskLink link) {
        return jdbc.sql("INSERT INTO defect_task (defect_id, task_id, created_by) "
                + "SELECT d.id, :task, :by FROM defect d WHERE d.id = :defect "
                + "AND NOT EXISTS (SELECT 1 FROM defect_task x WHERE x.defect_id = :defect AND x.task_id = :task)")
                .param("task", link.taskId().value())
                .param("by", link.createdBy().value())
                .param("defect", link.defectId().value())
                .update() == 1;
    }

    public boolean remove(DefectId defect, TaskId task) {
        return jdbc.sql("DELETE FROM defect_task WHERE defect_id = :defect AND task_id = :task")
                .param("defect", defect.value()).param("task", task.value()).update() == 1;
    }

    public List<DefectTaskLink> tasksOf(DefectId defect) {
        return jdbc.sql("SELECT defect_id, task_id, created_by FROM defect_task WHERE defect_id = :defect "
                + "ORDER BY task_id").param("defect", defect.value())
                .query((rs, n) -> new DefectTaskLink(DefectId.of(rs.getString("defect_id")),
                        TaskId.of(rs.getString("task_id")), ActorId.ofStored(rs.getString("created_by"))))
                .list();
    }

    public boolean add(DefectRelation relation) {
        return jdbc.sql("INSERT INTO defect_relation (a, b, kind, created_by) "
                + "SELECT d.id, :b, :kind, :by FROM defect d WHERE d.id = :a "
                + "AND NOT EXISTS (SELECT 1 FROM defect_relation x WHERE x.a = :a AND x.b = :b AND x.kind = :kind)")
                .param("b", relation.b().value())
                .param("kind", relation.relation().value())
                .param("by", relation.createdBy().value())
                .param("a", relation.a().value())
                .update() == 1;
    }

    public boolean remove(DefectRelation relation) {
        return jdbc.sql("DELETE FROM defect_relation WHERE a = :a AND b = :b AND kind = :kind")
                .param("a", relation.a().value()).param("b", relation.b().value())
                .param("kind", relation.relation().value()).update() == 1;
    }

    public List<DefectRelation> relationsOf(DefectId defect) {
        return jdbc.sql("SELECT a, b, kind, created_by FROM defect_relation WHERE a = :d OR b = :d "
                + "ORDER BY a, b, kind").param("d", defect.value())
                .query((rs, n) -> new DefectRelation(DefectId.of(rs.getString("a")), DefectId.of(rs.getString("b")),
                        CodeValue.of(rs.getString("kind")), ActorId.ofStored(rs.getString("created_by"))))
                .list();
    }

    public boolean add(ConditionLink link) {
        boolean toTask = link.taskId() != null;
        String sql = toTask
                ? "INSERT INTO condition_link (link_key, condition_id, task_id, created_by) "
                        + "SELECT :key, c.id, :target, :by FROM release_condition c WHERE c.id = :condition "
                        + "AND NOT EXISTS (SELECT 1 FROM condition_link x WHERE x.link_key = :key)"
                : "INSERT INTO condition_link (link_key, condition_id, defect_id, created_by) "
                        + "SELECT :key, c.id, :target, :by FROM release_condition c WHERE c.id = :condition "
                        + "AND NOT EXISTS (SELECT 1 FROM condition_link x WHERE x.link_key = :key)";
        return jdbc.sql(sql)
                .param("key", link.linkKey())
                .param("target", toTask ? link.taskId().value() : link.defectId().value())
                .param("by", link.createdBy().value())
                .param("condition", link.conditionId().value())
                .update() == 1;
    }

    public boolean remove(ConditionLink link) {
        return jdbc.sql("DELETE FROM condition_link WHERE link_key = :key").param("key", link.linkKey())
                .update() == 1;
    }

    /** Ids of the conditions linked to a defect, in id order. */
    public List<String> conditionsOfDefect(DefectId defect) {
        return jdbc.sql("SELECT condition_id FROM condition_link WHERE defect_id = :d ORDER BY condition_id")
                .param("d", defect.value()).query(String.class).list();
    }

    /** Ids of the conditions linked to a task, in id order. */
    public List<String> conditionsOfTask(TaskId task) {
        return jdbc.sql("SELECT condition_id FROM condition_link WHERE task_id = :t ORDER BY condition_id")
                .param("t", task.value()).query(String.class).list();
    }

    /** Ids of the defects a task works on, in id order. */
    public List<String> defectsOfTask(TaskId task) {
        return jdbc.sql("SELECT defect_id FROM defect_task WHERE task_id = :t ORDER BY defect_id")
                .param("t", task.value()).query(String.class).list();
    }

    public List<ConditionLink> linksOf(ConditionId condition) {
        return jdbc.sql("SELECT condition_id, task_id, defect_id, created_by FROM condition_link "
                + "WHERE condition_id = :c ORDER BY link_key").param("c", condition.value())
                .query((rs, n) -> {
                    String task = rs.getString("task_id");
                    ActorId by = ActorId.ofStored(rs.getString("created_by"));
                    ConditionId cond = ConditionId.of(rs.getString("condition_id"));
                    return task != null ? ConditionLink.toTask(cond, TaskId.of(task), by)
                            : ConditionLink.toDefect(cond, DefectId.of(rs.getString("defect_id")), by);
                }).list();
    }
}
