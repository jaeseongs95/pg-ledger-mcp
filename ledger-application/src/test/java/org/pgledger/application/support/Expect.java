package org.pgledger.application.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;

/** Assertions on the business failures of the services. */
public final class Expect {

    private Expect() {
    }

    /** The English message of a failure, resolved from the message bundle of the domain. */
    public static String message(LedgerException failure) {
        return new java.text.MessageFormat(java.util.ResourceBundle.getBundle("messages/ledger-errors",
                java.util.Locale.ROOT).getString(failure.code().messageKey())).format(failure.args().toArray());
    }

    /** What the call (run once) ended with: no value for a success, the error code of a business failure. */
    public static java.util.Optional<ErrorCode> outcome(ThrowingCallable call) {
        Throwable failure = catchThrowable(call);
        if (failure == null) {
            return java.util.Optional.empty();
        }
        assertThat(failure).as("only business failures are expected").isInstanceOf(LedgerException.class);
        return java.util.Optional.of(((LedgerException) failure).code());
    }

    /** The call (run once) must fail with a {@link LedgerException} of exactly this code; it is returned. */
    public static LedgerException failure(ThrowingCallable call, ErrorCode code) {
        Throwable failure = catchThrowable(call);
        assertThat(failure).as("the call must fail with " + code).isInstanceOf(LedgerException.class);
        LedgerException ledger = (LedgerException) failure;
        assertThat(ledger.code()).as("error code of %s", ledger).isEqualTo(code);
        return ledger;
    }
}
