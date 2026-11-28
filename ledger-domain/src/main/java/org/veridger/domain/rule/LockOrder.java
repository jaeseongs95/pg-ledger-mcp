// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import java.util.Comparator;
import java.util.function.Function;
import org.veridger.domain.value.ProjectId;

/**
 * The one order in which the rows of a project and of a session grant are locked. Two transactions that lock the same rows in
 * different orders can wait for each other, so every path that locks them sorts in Java with the comparators of this class and
 * locks in the sorted order: the write gate, the batch, the project moves, the correction and the revocation of grants. No path
 * relies on an {@code ORDER BY}, because the order of a database depends on its collation (en_US.UTF-8 puts {@code alpha}
 * before {@code Zeta}, the order of {@code String} puts it after).
 *
 * <p>After the grants, a write of an issue relation holds the code of its relation kind ({@code CommonCodeRepository.holdDirection}:
 * the owner's change of a direction waits for it), and a batch holds the codes of all its relation kinds before the first
 * write, sorted by the characters of the code (Phase 1.6 of {@code BatchLockPlanner}).
 *
 * <p>The order of the numbers, which the same transactions take last, is documented at {@code Counter}.
 */
public final class LockOrder {

    /**
     * Projects by the characters of their id ({@code String.compareTo}). A project id is {@code [A-Za-z0-9_-]{1,64}}, so this
     * is the order of the bytes: {@code Zeta} before {@code _x} before {@code a-b} before {@code alpha}.
     */
    public static final Comparator<ProjectId> PROJECT = Comparator.comparing(ProjectId::value);

    /** Scopes by their stored names, independent of enum declaration order. */
    public static final Comparator<Scope> SCOPE = Comparator.comparing(Scope::name);

    private LockOrder() {
    }

    /** The order of the grant rows of the callers' type: by project, then by scope. */
    public static <T> Comparator<T> grant(Function<T, ProjectId> project, Function<T, Scope> scope) {
        return Comparator.comparing(project, PROJECT).thenComparing(scope, SCOPE);
    }
}
