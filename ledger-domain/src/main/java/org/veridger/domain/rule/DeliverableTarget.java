// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.Token;

/** Entity kinds that can own a recorded deliverable. */
public enum DeliverableTarget implements Token {
    ISSUE("issue"),
    SPEC("spec"),
    TASK("task");

    private final String token;

    DeliverableTarget(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public HistoryEntity historyEntity() {
        return switch (this) {
            case ISSUE -> HistoryEntity.ISSUE;
            case SPEC -> HistoryEntity.SPEC;
            case TASK -> HistoryEntity.TASK;
        };
    }
}
