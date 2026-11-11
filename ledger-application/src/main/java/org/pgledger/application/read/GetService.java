package org.pgledger.application.read;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.pgledger.database.repository.EntityLookupRepository;
import org.pgledger.database.repository.EntityLookupRepository.Found;
import org.pgledger.domain.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The state and version of many ids at once, whatever their kind (the check after a write, and the way to get an
 * {@code expected_version}). An id that is both a defect id and a task id gives both items.
 */
@Service
@Transactional(readOnly = true)
public class GetService {

    /** The most ids one call may ask for. */
    public static final int MAX_IDS = 200;

    /** One found item: its kind (defect, task, condition) and its lifecycle state. */
    public record Item(String entity, String id, String state, long version, String projectId, String mergedInto) {
    }

    /** The items in the order of the request, and the ids that are no defect, task or condition. */
    public record Result(List<Item> items, List<String> notFound) {

        public Result {
            items = List.copyOf(items);
            notFound = List.copyOf(notFound);
        }
    }

    private final EntityLookupRepository lookup;

    public GetService(EntityLookupRepository lookup) {
        this.lookup = lookup;
    }

    public Result get(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw ValidationException.invalid("ids", "must hold 1.." + MAX_IDS + " ids");
        }
        List<String> distinct = List.copyOf(new LinkedHashSet<>(ids));
        Map<String, Found> defects = byId(lookup.defects(distinct));
        Map<String, Found> tasks = byId(lookup.tasks(distinct));
        Map<String, Found> conditions = byId(lookup.conditions(distinct));
        List<Item> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : distinct) {
            int before = items.size();
            add(items, "defect", defects.get(id));
            add(items, "task", tasks.get(id));
            add(items, "condition", conditions.get(id));
            if (items.size() == before) {
                missing.add(id);
            }
        }
        return new Result(items, missing);
    }

    private static Map<String, Found> byId(List<Found> found) {
        return found.stream().collect(Collectors.toMap(Found::id, Function.identity()));
    }

    private static void add(List<Item> items, String entity, Found found) {
        if (found != null) {
            items.add(new Item(entity, found.id(), found.state(), found.version(), found.projectId(),
                    found.mergedInto()));
        }
    }
}
