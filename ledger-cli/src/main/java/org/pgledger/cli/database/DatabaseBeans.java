package org.pgledger.cli.database;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one answer of a command to a bean that is not there because no usable JDBC driver is registered ({@link
 * CliDatabaseCondition}): DRIVER_NOT_CONFIGURED. Every command that works on the ledger database asks for its door through
 * this, so none repeats the check.
 */
public final class DatabaseBeans {

    private DatabaseBeans() {
    }

    /**
     * The bean of the database configuration.
     *
     * @throws FailureException DRIVER_NOT_CONFIGURED when there is none
     */
    public static <T> T require(ObjectProvider<T> provider) {
        return provider.getIfAvailable(() -> {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        });
    }
}
