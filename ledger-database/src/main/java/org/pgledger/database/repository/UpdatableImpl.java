package org.pgledger.database.repository;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;

/** Spring Data fragment implementation of {@link Updatable}. */
public class UpdatableImpl<T> implements Updatable<T> {

    private final JdbcAggregateOperations operations;

    public UpdatableImpl(JdbcAggregateOperations operations) {
        this.operations = operations;
    }

    @Override
    public T update(T aggregate) {
        return operations.update(aggregate);
    }
}
