package org.pgledger.domain.rule;

/** Whether a fix row was recorded by this ledger or copied from an older one. */
public enum FixOrigin {
    NATIVE, IMPORTED
}
