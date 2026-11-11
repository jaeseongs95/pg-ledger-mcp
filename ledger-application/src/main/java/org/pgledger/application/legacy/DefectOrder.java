package org.pgledger.application.legacy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.database.repository.LegacyRow;

/**
 * The order in which the defects are inserted: a merged defect points at its merge target ({@code merged_into}, a foreign
 * key to the same table), so the target is inserted first, whatever the order of the ids. The legacy schema could defer
 * that foreign key; the new schema does not, and the import uses no DBMS-specific syntax for it, so it orders the rows.
 * A chain that leads back to itself cannot be ordered; its defects are reported.
 *
 * @param ordered the defects that can be inserted, every merge target before the defects merged into it
 * @param loops   the ids of the defects whose merge chain never ends
 */
record DefectOrder(List<LegacyRow> ordered, List<String> loops) {

    static DefectOrder of(List<LegacyRow> defects) {
        Map<String, LegacyRow> byId = new LinkedHashMap<>();
        for (LegacyRow defect : defects) {
            byId.put(defect.text("id"), defect);
        }
        List<LegacyRow> ordered = new ArrayList<>();
        List<String> loops = new ArrayList<>();
        Set<String> settled = new HashSet<>();
        for (LegacyRow start : defects) {
            List<LegacyRow> chain = new ArrayList<>();
            Set<String> inChain = new HashSet<>();
            LegacyRow cursor = start;
            boolean loop = false;
            while (cursor != null && !settled.contains(cursor.text("id"))) {
                if (!inChain.add(cursor.text("id"))) {
                    loop = true;
                    break;
                }
                chain.add(cursor);
                String next = cursor.text("merged_into");
                cursor = next == null ? null : byId.get(next);
            }
            if (loop) {
                for (LegacyRow member : chain) {
                    loops.add(member.text("id"));
                    settled.add(member.text("id"));
                }
            } else {
                for (int i = chain.size() - 1; i >= 0; i--) {
                    ordered.add(chain.get(i));
                    settled.add(chain.get(i).text("id"));
                }
            }
        }
        return new DefectOrder(List.copyOf(ordered), List.copyOf(loops));
    }
}
