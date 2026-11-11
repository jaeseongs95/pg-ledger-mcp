package org.pgledger.domain.rule;

/** Whether a receipt was written by this ledger or imported (LEGACY receipts are never replayed). */
public enum ReceiptOrigin {
    NATIVE, LEGACY
}
