// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import java.util.Arrays;
import java.util.List;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.value.Token;

/** What a session grant allows on a project. */
public enum Scope implements Token {
    ISSUE("issue"),
    SPEC("spec"),
    TASK("task"),
    DELIVERABLE("deliverable"),
    ISSUE_CREATE("issue_create", ISSUE),
    ISSUE_UPDATE("issue_update", ISSUE),
    SPEC_CREATE("spec_create", SPEC),
    SPEC_UPDATE("spec_update", SPEC),
    TASK_CREATE("task_create", TASK),
    TASK_UPDATE("task_update", TASK),
    CRITERION_CREATE("criterion_create", TASK),
    CRITERION_UPDATE("criterion_update", TASK),
    DELIVERABLE_ADD("deliverable_create", DELIVERABLE),
    DELIVERABLE_EDIT("deliverable_update", DELIVERABLE),
    VERSION_CREATE("version_create", ISSUE),
    VERSION_UPDATE("version_update", ISSUE),
    VERIFY("verify");

    private final String token;

    private final Scope bundledScope;

    Scope(String token) {
        this(token, null);
    }

    Scope(String token, Scope bundledScope) {
        this.token = token;
        this.bundledScope = bundledScope;
    }

    /** Older grants cover the same actions they covered before the split. */
    public Scope bundledScope() {
        return bundledScope;
    }

    public static List<Scope> assignable() {
        return Arrays.stream(values()).filter(scope -> scope.bundledScope != null || scope == VERIFY).toList();
    }

    public List<Scope> effectiveScopes() {
        return switch (this) {
            case ISSUE, SPEC, TASK, DELIVERABLE ->
                assignable().stream().filter(scope -> scope.bundledScope == this).toList();
            default -> List.of(this);
        };
    }

    @Override
    public String token() {
        return token;
    }

    /**
     * The scope a state change needs: VERIFY when entering or leaving a verification state, otherwise the write scope
     * of the entity.
     */
    public static Scope forTransition(HistoryEntity entity, LifecycleState from, LifecycleState to) {
        if (from.verification() || to.verification()) {
            return VERIFY;
        }
        return switch (entity) {
            case ISSUE, SPEC, TASK, CRITERION -> entity.updateScope();
            case DELIVERABLE, ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE ->
                throw ValidationException.invalid("entity", ValidationReason.HAS_NO_STATE, entity.token());
        };
    }
}
