package org.pgledger.database.repository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.pgledger.domain.value.DefectId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Follows the merges of defects. The chain is walked level by level with plain IN-list selects (no recursive SQL);
 * a defect already seen is not followed again, so the walk always ends.
 */
@Repository
public class MergeChainRepository {

    private final JdbcClient jdbc;

    public MergeChainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every defect that was merged into {@code root}, directly or through other merged defects. */
    public Set<DefectId> mergedInto(DefectId root) {
        Set<DefectId> found = new LinkedHashSet<>();
        Set<DefectId> seen = new LinkedHashSet<>(List.of(root));
        List<DefectId> level = List.of(root);
        while (!level.isEmpty()) {
            List<String> parents = level.stream().map(DefectId::value).toList();
            List<DefectId> next = new ArrayList<>();
            for (String id : jdbc.sql("SELECT id FROM defect WHERE merged_into IN (:parents) ORDER BY id")
                    .param("parents", parents).query(String.class).list()) {
                DefectId child = DefectId.of(id);
                if (seen.add(child)) {
                    found.add(child);
                    next.add(child);
                }
            }
            level = next;
        }
        return found;
    }
}
