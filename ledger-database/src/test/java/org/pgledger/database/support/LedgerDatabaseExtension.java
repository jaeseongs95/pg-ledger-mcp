package org.pgledger.database.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension for the database tests. It hands {@link Databases} (one per run, with the embedded PostgreSQL
 * server inside) and the shared, migrated ledger database of the test's DBMS ({@link LedgerTestContext}) to a test
 * or lifecycle method as parameters. Both live in the root store, are created on first use and are closed by JUnit
 * when the run ends, the contexts first and the server last. A test class that wants the migrated database
 * implements {@link DbmsAware} (it is a {@code @ParameterizedClass}).
 */
public final class LedgerDatabaseExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(
            LedgerDatabaseExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == LedgerTestContext.class || type == Databases.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        if (parameter.getParameter().getType() == Databases.class) {
            return databases(context);
        }
        Object instance = context.getTestInstance().orElseThrow(
                () -> new ParameterResolutionException("the context is available to test methods, not constructors"));
        if (!(instance instanceof DbmsAware aware)) {
            throw new ParameterResolutionException(instance.getClass() + " must implement DbmsAware");
        }
        Databases databases = databases(context);
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(aware.dbms(),
                dbms -> LedgerTestContext.create(databases, dbms), LedgerTestContext.class);
    }

    private static Databases databases(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(Databases.class, type -> new Databases(),
                Databases.class);
    }
}
