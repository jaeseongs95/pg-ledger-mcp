package org.pgledger.database.repository;

/**
 * Repository fragment for aggregates that are written once with an id the application assigned (receipts,
 * history, cases, grants ...). {@code save} cannot tell a new aggregate with an assigned id from an existing one, so
 * these repositories offer an explicit insert instead and no delete.
 */
public interface Insertable<T> {

    /** Inserts the aggregate and returns it as stored (versions and audit fields filled in). */
    T insert(T aggregate);
}
