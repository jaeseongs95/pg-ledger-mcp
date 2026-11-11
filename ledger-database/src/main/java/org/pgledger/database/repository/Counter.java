package org.pgledger.database.repository;

/** The rows of {@code id_counter}: the surrogate keys that need a sequence of numbers (no IDENTITY, no sequence). */
public enum Counter {
    HISTORY, DEFECT_FIX, DEFECT_CASE, SESSION_GRANT, SESSION_KEY, OWNER_AUDIT
}
