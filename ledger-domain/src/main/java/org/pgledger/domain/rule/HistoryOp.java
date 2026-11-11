package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** What a history entry records. The last three exist only for rows imported from an older ledger. */
public enum HistoryOp implements Token {
    CREATE, EDIT, TRANSITION, LINK, UNLINK, CORRECT, IMPORT, LEGACY_NOTE, LEGACY_CHANGE;

    @Override
    public String token() {
        return name();
    }

    public boolean legacyOnly() {
        return this == IMPORT || this == LEGACY_NOTE || this == LEGACY_CHANGE;
    }
}
