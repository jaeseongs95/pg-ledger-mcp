package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.ValidationCode;

/** Shared assertions of the domain tests. */
final class Expect {

    private Expect() {
    }

    /** The call fails with a {@link LedgerException} that carries {@code code}. */
    static LedgerException failsWith(ErrorCode code, ThrowingCallable call) {
        LedgerException[] caught = new LedgerException[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(LedgerException.class, e -> {
            assertThat(e.code()).as("code of %s", e).isEqualTo(code);
            caught[0] = e;
        });
        return caught[0];
    }

    static LedgerException invalid(ThrowingCallable call) {
        return failsWith(ValidationCode.VALIDATION_FAILED, call);
    }
}
