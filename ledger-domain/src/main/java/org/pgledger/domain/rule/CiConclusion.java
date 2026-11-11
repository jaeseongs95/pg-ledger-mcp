package org.pgledger.domain.rule;

import org.pgledger.domain.value.Token;

/** Outcome of the CI run behind a fix commit. */
public enum CiConclusion implements Token {
    SUCCESS("success"), FAILURE("failure"), CANCELLED("cancelled");

    private final String token;

    CiConclusion(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }
}
