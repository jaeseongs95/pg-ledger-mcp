// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.Token;

/** The kind of row a history entry is about. */
public enum HistoryEntity implements Token {
    ISSUE("issue"),
    SPEC("spec"),
    TASK("task"),
    CRITERION("criterion"),
    DELIVERABLE("deliverable"),
    ISSUE_TASK("issue_task"),
    ISSUE_SPEC("issue_spec"),
    SPEC_TASK("spec_task"),
    ISSUE_RELATION("issue_relation"),
    CRITERION_LINK("criterion_link"),
    ISSUE_CASE("issue_case");

    private final String token;

    HistoryEntity(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public Scope createScope() {
        return switch (this) {
            case ISSUE -> Scope.ISSUE_CREATE;
            case SPEC -> Scope.SPEC_CREATE;
            case TASK -> Scope.TASK_CREATE;
            case CRITERION -> Scope.CRITERION_CREATE;
            case DELIVERABLE -> Scope.DELIVERABLE_ADD;
            case ISSUE_TASK, ISSUE_SPEC, SPEC_TASK, ISSUE_RELATION, CRITERION_LINK, ISSUE_CASE -> updateScope();
        };
    }

    /** Updates to relationships and observations belong to their owning record. */
    public Scope updateScope() {
        return switch (this) {
            case ISSUE, ISSUE_TASK, ISSUE_RELATION, ISSUE_CASE -> Scope.ISSUE_UPDATE;
            case SPEC, ISSUE_SPEC, SPEC_TASK -> Scope.SPEC_UPDATE;
            case TASK -> Scope.TASK_UPDATE;
            case CRITERION, CRITERION_LINK -> Scope.CRITERION_UPDATE;
            case DELIVERABLE -> Scope.DELIVERABLE_EDIT;
        };
    }
}
