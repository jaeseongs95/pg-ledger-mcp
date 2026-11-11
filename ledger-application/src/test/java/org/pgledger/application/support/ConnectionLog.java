package org.pgledger.application.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A DataSource that records how its connections are set up and ended: the calls of {@code setReadOnly},
 * {@code setTransactionIsolation}, {@code commit} and {@code rollback}, as {@code name[arguments]} (test spy; the proxy
 * is test-only). The legacy import must read in a read-only transaction of the isolation REPEATABLE READ (MIG-008).
 */
public final class ConnectionLog extends DelegatingDataSource {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    public ConnectionLog(DataSource target) {
        super(target);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("setReadOnly".equals(name) || "setTransactionIsolation".equals(name) || "commit".equals(name)
                            || "rollback".equals(name)) {
                        calls.add(name + (args == null ? "[]" : Arrays.toString(args)));
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
