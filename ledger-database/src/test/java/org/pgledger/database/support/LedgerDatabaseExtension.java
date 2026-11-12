package org.pgledger.database.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension for the database tests. It hands {@link Databases} and the shared, migrated ledger database
 * ({@link LedgerTestContext}) to a test or lifecycle method as parameters. {@link Databases} is made for the test that
 * asks (the store of that test, so its schemas are dropped when that test ends, also after a failure). The shared
 * context lives in the root store, is created on first use and is closed by JUnit when the run ends (the context closes
 * its Spring context and drops its schema).
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
            return context.getStore(NAMESPACE).computeIfAbsent(Databases.class, type -> new Databases(),
                    Databases.class);
        }
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(LedgerTestContext.class,
                type -> LedgerTestContext.create(new Databases()), LedgerTestContext.class);
    }
}
