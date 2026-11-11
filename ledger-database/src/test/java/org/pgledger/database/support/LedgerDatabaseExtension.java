package org.pgledger.database.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension that hands the shared, migrated ledger database of the test's DBMS to a test or lifecycle method as
 * a {@link LedgerTestContext} parameter. The contexts live in the root store, are created on first use and closed
 * when the test run ends. The test class implements {@link DbmsAware} (it is a {@code @ParameterizedClass}).
 */
public final class LedgerDatabaseExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(
            LedgerDatabaseExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == LedgerTestContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Object instance = context.getTestInstance().orElseThrow(
                () -> new ParameterResolutionException("the context is available to test methods, not constructors"));
        if (!(instance instanceof DbmsAware aware)) {
            throw new ParameterResolutionException(instance.getClass() + " must implement DbmsAware");
        }
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(aware.dbms(), LedgerTestContext::create,
                LedgerTestContext.class);
    }
}
