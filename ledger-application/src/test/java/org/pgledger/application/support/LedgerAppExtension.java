package org.pgledger.application.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension for the service tests. It hands {@link Databases} (one per run), the shared migrated {@link LedgerAppContext} of the test's DBMS and a new {@link Scenario} (a
 * project of its own) to a test method as parameters. Databases and contexts live in the root store, are created on
 * first use and closed by JUnit when the run ends, the contexts first and the server last. A test class that wants
 * them implements {@link DbmsAware} (it is a parameterized class).
 */
public final class LedgerAppExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(
            LedgerAppExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == Scenario.class || type == LedgerAppContext.class || type == Databases.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        if (type == Databases.class) {
            return databases(context);
        }
        Object instance = context.getTestInstance().orElseThrow(
                () -> new ParameterResolutionException("the context is available to test methods, not constructors"));
        if (!(instance instanceof DbmsAware aware)) {
            throw new ParameterResolutionException(instance.getClass() + " must implement DbmsAware");
        }
        Databases databases = databases(context);
        LedgerAppContext shared = context.getRoot().getStore(NAMESPACE).computeIfAbsent(aware.dbms(),
                dbms -> LedgerAppContext.create(databases, dbms), LedgerAppContext.class);
        return type == LedgerAppContext.class ? shared : new Scenario(shared);
    }

    private static Databases databases(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(Databases.class, type -> new Databases(),
                Databases.class);
    }
}
