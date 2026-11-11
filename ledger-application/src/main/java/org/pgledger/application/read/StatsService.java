package org.pgledger.application.read;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.StatsQueryRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.ConditionState;
import org.pgledger.domain.rule.DefectStatus;
import org.pgledger.domain.rule.TaskState;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Token;
import org.pgledger.domain.value.Tokens;
import org.pgledger.database.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts per state: defects by status and severity, tasks by state, conditions by state, of one project or of the
 * whole ledger. Every state of a closed set is present (zero when empty); the severity codes of the list are present
 * too, and a retired severity that rows still hold is shown with them. With {@code bucket} = {@code day} the
 * new defects and tasks per UTC day are added; a day is cut from the stored UTC time, never from a local one.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    /** The time buckets a call may ask for. */
    public enum Bucket implements Token {
        DAY;

        @Override
        public String token() {
            return "day";
        }
    }

    /** The project (absent for all) and the bucket (absent, or {@code day}). */
    public record StatsQuery(String project, String bucket) {
    }

    /** New rows of one UTC day. */
    public record DayCount(LocalDate day, long defects, long tasks) {
    }

    /** The counts; the totals are the sums of the maps. */
    public record Stats(Map<String, Long> defectsByStatus, Map<String, Long> defectsBySeverity,
            Map<String, Long> tasksByState, Map<String, Long> conditionsByState, long defects, long tasks,
            long conditions, List<DayCount> createdPerDay) {

        public Stats {
            defectsByStatus = ordered(defectsByStatus);
            defectsBySeverity = ordered(defectsBySeverity);
            tasksByState = ordered(tasksByState);
            conditionsByState = ordered(conditionsByState);
            createdPerDay = List.copyOf(createdPerDay);
        }
    }

    private final StatsQueryRepository stats;
    private final ProjectRepository projects;
    private final CommonCodeRepository codes;

    public StatsService(StatsQueryRepository stats, ProjectRepository projects, CommonCodeRepository codes) {
        this.stats = stats;
        this.projects = projects;
        this.codes = codes;
    }

    public Stats stats(StatsQuery q) {
        ProjectId project = q.project() == null || q.project().isBlank() ? null : ProjectId.of(q.project());
        if (project != null && !projects.existsById(project)) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", project.value()));
        }
        boolean daily = q.bucket() != null && !q.bucket().isBlank()
                && Tokens.parse("bucket", q.bucket(), Bucket.values()) == Bucket.DAY;
        Map<String, Long> byStatus = filled(stats.defectsByStatus(project),
                List.of(DefectStatus.values()).stream().map(Enum::name).toList());
        Map<String, Long> bySeverity = filled(stats.defectsBySeverity(project),
                codes.catalog(CodeGroup.DEFECT_SEVERITY).entries().stream().map(c -> c.code().value()).toList());
        Map<String, Long> byTask = filled(stats.tasksByState(project),
                List.of(TaskState.values()).stream().map(Enum::name).toList());
        Map<String, Long> byCondition = filled(stats.conditionsByState(project),
                List.of(ConditionState.values()).stream().map(Enum::name).toList());
        List<DayCount> days = daily ? days(project) : List.of();
        return new Stats(byStatus, bySeverity, byTask, byCondition, sum(byStatus), sum(byTask), sum(byCondition),
                days);
    }

    private List<DayCount> days(ProjectId project) {
        Map<LocalDate, Long> defects = stats.defectsCreatedPerDay(project);
        Map<LocalDate, Long> tasks = stats.tasksCreatedPerDay(project);
        TreeSet<LocalDate> all = new TreeSet<>(defects.keySet());
        all.addAll(tasks.keySet());
        return all.stream().map(day -> new DayCount(day, defects.getOrDefault(day, 0L),
                tasks.getOrDefault(day, 0L))).toList();
    }

    private static Map<String, Long> filled(Map<String, Long> counted, List<String> keys) {
        Map<String, Long> out = new LinkedHashMap<>();
        keys.forEach(key -> out.put(key, counted.getOrDefault(key, 0L)));
        counted.forEach(out::putIfAbsent);
        return out;
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Map<String, Long> ordered(Map<String, Long> counts) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
