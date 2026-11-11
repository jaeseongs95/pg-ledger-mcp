package org.pgledger.database.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension for the database tests. It hands {@link Databases} and the shared, migrated ledger database
 * ({@link LedgerTestContext}) to a test or lifecycle method as parameters. Both live in the root store, are created
 * on first use and are closed by JUnit when the run ends (the context closes its pool and Spring context).
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
        Databases databases = context.getRoot().getStore(NAMESPACE).computeIfAbsent(Databases.class,
                type -> new Databases(), Databases.class);
        if (parameter.getParameter().getType() == Databases.class) {
            return databases;
        }
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(LedgerTestContext.class,
                type -> LedgerTestContext.create(databases), LedgerTestContext.class);
    }
}
