package org.pgledger.domain.model;

/** The operation a receipt was issued for; a request id may be reused only for the same operation. */
public enum ReceiptOperation {
    SAVE, TRANSITION, RECORD_FIX, RECORD_CASE, LINK, CORRECT, BATCH
}
