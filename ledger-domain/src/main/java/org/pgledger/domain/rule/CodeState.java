package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** Whether a common code may be used by new writes. Codes are never deleted. */
public enum CodeState implements Token {
    ACTIVE, INACTIVE;

    @Override
    public String token() {
        return name();
    }
}
