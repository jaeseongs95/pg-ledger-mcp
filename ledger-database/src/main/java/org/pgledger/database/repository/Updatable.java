package org.pgledger.database.repository;

/** Repository fragment for aggregates with an assigned id that are updated in place without a version column. */
public interface Updatable<T> {

    /** Updates the stored row of the aggregate (by id); fails when no row was updated. */
    T update(T aggregate);
}
