package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** The extensible code lists. Only these three exist; other value sets are fixed enums. */
public enum CodeGroup implements Token {
    DEFECT_CATEGORY(false), DEFECT_SEVERITY(true), RELATION_KIND(false);

    private final boolean alwaysActive;

    CodeGroup(boolean alwaysActive) {
        this.alwaysActive = alwaysActive;
    }

    @Override
    public String token() {
        return name();
    }

    /** True when every write of a row needs an active code, even if the row keeps its old value (severity). */
    public boolean alwaysActive() {
        return alwaysActive;
    }
}
