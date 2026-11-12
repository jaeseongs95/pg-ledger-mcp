package org.pgledger.mcp.diagnostics;

import java.util.Locale;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Explains why the server does not start when no JDBC driver is registered (or the registered one is not usable):
 * the reason and what to do, in English, instead of a stack trace. The text comes from the message bundle of the
 * error codes.
 */
public class DriverFailureAnalyzer extends AbstractFailureAnalyzer<InfrastructureException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InfrastructureException cause) {
        if (cause.code() != InfrastructureCode.DRIVER_NOT_CONFIGURED
                && cause.code() != InfrastructureCode.DRIVER_INVALID) {
            return null;
        }
        // made with new: Spring Boot creates a failure analyzer from spring.factories (reflection, no-argument constructor)
        // while the application context failed to start, so no container exists to inject a MessageSource from
        ResourceBundleMessageSource bundles = new ResourceBundleMessageSource();
        bundles.setBasename("messages/ledger-errors");
        bundles.setFallbackToSystemLocale(false);
        String reason = bundles.getMessage(cause.code().messageKey(), cause.args().toArray(), cause.code().token(),
                Locale.ENGLISH);
        return new FailureAnalysis(cause.code().token() + ": " + reason,
                "Register a JDBC driver (the driver-register command of ledger-cli) and start the server again.",
                cause);
    }
}
