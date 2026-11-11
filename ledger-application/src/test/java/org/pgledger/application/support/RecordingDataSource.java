package org.pgledger.application.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** A DataSource that records every SQL text that passes through it (test spy; the proxies are test-only). */
public final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    public RecordingDataSource(DataSource target) {
        super(target);
    }

    public List<String> statements() {
        return List.copyOf(statements);
    }

    public void clear() {
        statements.clear();
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
                    Object result = invoke(connection, method, args);
                    if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                        statements.add(sql);
                    }
                    if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                        return wrapStatement(statement);
                    }
                    return result;
                });
    }

    private Statement wrapStatement(Statement statement) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute") && args != null && args.length > 0
                            && args[0] instanceof String sql) {
                        statements.add(sql);
                    }
                    return invoke(statement, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
