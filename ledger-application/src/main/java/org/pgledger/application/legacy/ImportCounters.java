package org.pgledger.application.legacy;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.database.repository.Counter;
import org.pgledger.domain.value.DefectId;

/**
 * The highest numbers the legacy ledger has used, which the new ledger continues from: per project the number of the
 * last defect ({@code <CODE>-D-NNNN}) and of the last task ({@code <CODE>-T-NNNN}; a task with another id counts for
 * nothing), and the highest id of each surrogate key sequence (history, fix, case, grant). The next number issued is
 * one above, so it collides with nothing.
 */
final class ImportCounters {

    private final Map<String, String> codeByProject = new HashMap<>();
    private final Map<String, Pattern> taskIdByProject = new HashMap<>();
    private final Map<String, Long> defectNumbers = new HashMap<>();
    private final Map<String, Long> taskNumbers = new HashMap<>();
    private final Map<Counter, Long> surrogate = new EnumMap<>(Counter.class);

    void project(String projectId, String code) {
        if (projectId != null && code != null) {
            codeByProject.put(projectId, code);
            taskIdByProject.put(projectId, Pattern.compile(Pattern.quote(code) + "-T-([0-9]{4,})"));
        }
    }

    void defect(String projectId, String defectId) {
        String code = codeByProject.get(projectId);
        Optional<DefectId> id = DefectId.parse(defectId);
        if (code != null && id.isPresent() && code.equals(id.get().projectCode().value())) {
            defectNumbers.merge(projectId, id.get().number(), Math::max);
        }
    }

    void task(String projectId, String taskId) {
        Pattern pattern = taskIdByProject.get(projectId);
        if (pattern != null && taskId != null) {
            Matcher matcher = pattern.matcher(taskId);
            if (matcher.matches()) {
                taskNumbers.merge(projectId, Long.parseLong(matcher.group(1)), Math::max);
            }
        }
    }

    void surrogate(Counter counter, Long id) {
        if (id != null) {
            surrogate.merge(counter, id, Math::max);
        }
    }

    long defectNumber(String projectId) {
        return defectNumbers.getOrDefault(projectId, 0L);
    }

    long taskNumber(String projectId) {
        return taskNumbers.getOrDefault(projectId, 0L);
    }

    long highest(Counter counter) {
        return surrogate.getOrDefault(counter, 0L);
    }
}
