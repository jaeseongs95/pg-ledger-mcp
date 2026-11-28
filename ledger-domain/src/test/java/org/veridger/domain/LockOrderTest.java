// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.domain.rule.LockOrder;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.ProjectId;

/** LOCK-01 .. LOCK-03: the one order of the project and grant locks, which no collation changes. */
class LockOrderTest {

    private record Grant(String project, Scope scope) {
    }

    private static final List<String> BYTE_ORDER = List.of("9", "A1", "Zeta", "_x", "a-b", "a1", "a_b", "alpha");

    @Test
    @DisplayName("LOCK-01 projects are ordered by the bytes of their id, not by a collation: Zeta, _x, a-b and alpha keep one order")
    void projectsByBytes() {
        List<ProjectId> shuffled = new ArrayList<>(BYTE_ORDER.stream().map(ProjectId::of).toList());
        Collections.reverse(shuffled);
        assertThat(shuffled.stream().sorted(LockOrder.PROJECT).map(ProjectId::value)).containsExactlyElementsOf(BYTE_ORDER);
        // the premise of the rule: a linguistic collation, as a database may have, orders at least one pair the other way
        Collator linguistic = Collator.getInstance(Locale.US);
        assertThat(linguistic.compare("Zeta", "alpha")).isPositive();
        assertThat(LockOrder.PROJECT.compare(ProjectId.of("Zeta"), ProjectId.of("alpha"))).isNegative();
    }

    @Test
    @DisplayName("LOCK-02 scopes are ordered by their stored names")
    void scopesByName() {
        List<Scope> reversed = new ArrayList<>(List.of(Scope.values()));
        Collections.reverse(reversed);
        assertThat(reversed.stream().sorted(LockOrder.SCOPE).map(Scope::name)).containsExactly(
                "CRITERION_CREATE", "CRITERION_UPDATE", "DELIVERABLE", "DELIVERABLE_ADD", "DELIVERABLE_EDIT",
                "ISSUE", "ISSUE_CREATE", "ISSUE_UPDATE", "SPEC", "SPEC_CREATE", "SPEC_UPDATE",
                "TASK", "TASK_CREATE", "TASK_UPDATE", "VERIFY", "VERSION_CREATE", "VERSION_UPDATE");
    }

    @Test
    @DisplayName("LOCK-03 a grant is ordered by its project first and by its scope second, for every type that carries both")
    void grantsByProjectThenScope() {
        List<Grant> grants = new ArrayList<>();
        for (String project : BYTE_ORDER.reversed()) {
            for (Scope scope : List.of(Scope.TASK, Scope.ISSUE)) {
                grants.add(new Grant(project, scope));
            }
        }
        List<Grant> expected = new ArrayList<>();
        for (String project : BYTE_ORDER) {
            expected.add(new Grant(project, Scope.ISSUE));
            expected.add(new Grant(project, Scope.TASK));
        }
        assertThat(grants.stream().sorted(LockOrder.<Grant>grant(g -> ProjectId.of(g.project()), Grant::scope)))
                .containsExactlyElementsOf(expected);
    }
}
