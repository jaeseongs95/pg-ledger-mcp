package org.pgledger.database.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** A DataSource that records every SQL text and every parameter binding that passes through it (test spy). */
public final class RecordingDataSource extends DelegatingDataSource {

    /** One bound parameter: the statement text, the JDBC setter used and the Java type of the value. */
    public record Binding(String sql, String setter, Class<?> type) {
    }

    private final List<String> statements = new CopyOnWriteArrayList<>();
    private final List<Binding> bindings = new CopyOnWriteArrayList<>();

    public RecordingDataSource(DataSource target) {
        super(target);
    }

    public List<String> statements() {
        return List.copyOf(statements);
    }

    public List<Binding> bindings() {
        return List.copyOf(bindings);
    }

    public void clear() {
        statements.clear();
        bindings.clear();
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
                    if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql) {
                        statements.add(sql);
                        return wrapPrepared((PreparedStatement) result, sql);
                    }
                    if ("createStatement".equals(method.getName()) && result instanceof Statement statement) {
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

    private PreparedStatement wrapPrepared(PreparedStatement statement, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set") && args != null && args.length >= 2) {
                        Object value = args[1];
                        bindings.add(new Binding(sql, method.getName(), value == null ? null : value.getClass()));
                    }
                    return invoke(statement, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // rethrown unchanged: a proxy must show the caller the exception of the real call, not the reflection wrapper
            throw e.getCause();
        }
    }
}
