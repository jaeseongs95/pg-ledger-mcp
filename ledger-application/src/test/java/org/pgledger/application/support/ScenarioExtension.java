package org.pgledger.application.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JUnit extension of {@link LedgerServiceTest}. It hands the {@link LedgerAppContext} of the Spring test context of
 * the class and a new {@link Scenario} (a project of its own) to a test method as parameters.
 */
public final class ScenarioExtension implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == Scenario.class || type == LedgerAppContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ParameterResolutionException("a service test must not run inside a transaction: the race tests "
                    + "need their commits, and a rolled-back transaction hides what the services committed");
        }
        ApplicationContext application = SpringExtension.getApplicationContext(context);
        LedgerAppContext ctx = new LedgerAppContext(application);
        return parameter.getParameter().getType() == LedgerAppContext.class ? ctx : new Scenario(ctx);
    }
}
