package org.pgledger.domain.rule;

/** Through which tool family a write arrives; only the reviewer channel may record verifications. */
public enum Channel {
    AUTHOR("ledger_author"), REVIEWER("ledger_reviewer");

    private final String login;

    Channel(String login) {
        this.login = login;
    }

    /** The value recorded in the {@code login} column of history and receipts. */
    public String login() {
        return login;
    }

    /** Whether a write through this channel may record a verification. */
    public boolean mayVerify() {
        return this == REVIEWER;
    }
}
