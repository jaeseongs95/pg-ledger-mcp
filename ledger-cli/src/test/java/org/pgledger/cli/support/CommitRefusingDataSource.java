package org.pgledger.cli.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * A DataSource whose connections do everything the real ones do except commit: a commit is refused with an {@link SQLException}
 * (the way a database refuses a transaction at its end: a serialization failure, a deferred constraint, a lost connection). The
 * statements before it run and are rolled back by the transaction manager as for any failed commit. For the test of what a
 * command leaves behind when the end of its transaction fails.
 */
public final class CommitRefusingDataSource {

    /** The message of the refusal. */
    public static final String REFUSAL = "the commit is refused (test)";

    private CommitRefusingDataSource() {
    }

    public static DataSource around(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(CommitRefusingDataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    Object result = call(target, method, arguments);
                    return result instanceof Connection connection ? refusingCommit(connection) : result;
                });
    }

    private static Connection refusingCommit(Connection connection) {
        return (Connection) Proxy.newProxyInstance(CommitRefusingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if ("commit".equals(method.getName())) {
                        throw new SQLException(REFUSAL);
                    }
                    return call(connection, method, arguments);
                });
    }

    private static Object call(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
