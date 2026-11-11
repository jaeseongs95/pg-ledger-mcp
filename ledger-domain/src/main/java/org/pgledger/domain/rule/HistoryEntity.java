package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** The kind of row a history entry is about. */
public enum HistoryEntity implements Token {
    DEFECT("defect"),
    TASK("task"),
    CONDITION("condition"),
    DEFECT_FIX("defect_fix"),
    DEFECT_TASK("defect_task"),
    DEFECT_RELATION("defect_relation"),
    CONDITION_LINK("condition_link"),
    DEFECT_CASE("defect_case");

    private final String token;

    HistoryEntity(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }
}
