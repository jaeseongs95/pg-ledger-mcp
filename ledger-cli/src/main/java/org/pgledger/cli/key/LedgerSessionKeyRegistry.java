package org.pgledger.cli.key;

import java.util.List;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.database.CliActor;
import org.pgledger.cli.database.SchemaReadiness;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.dao.DataAccessException;

/**
 * The {@link SessionKeyRegistry} of a CLI that has the ledger database: it calls the application service
 * {@link SessionKeyService}, the same one the admin web uses for its console, and records the owner operation in the
 * audit trail as {@code cli:<name of the process user>} ({@link CliActor}); it also closes a key at the owner's command ({@code key-revoke}).
 * A refusal of the ledger becomes a {@link FailureException} of the
 * CLI (the ledger code is translated, its arguments are never echoed except the fingerprint and registration time the
 * owner needs to confirm a replacement); no key or secret is ever part of a message.
 */
public final class LedgerSessionKeyRegistry implements SessionKeyRegistry {

    private final SessionKeyService service;
    private final CliActor actor;
    private final SchemaReadiness readiness;

    public LedgerSessionKeyRegistry(SessionKeyService service, CliActor actor, SchemaReadiness readiness) {
        this.service = service;
        this.actor = actor;
        this.readiness = readiness;
    }

    @Override
    public void register(String author, String publicKey) {
        OwnerIdentity owner = actor.current();
        readiness.require();
        try {
            service.register(new RegisterCommand(author, publicKey, null), owner);
        } catch (LedgerException failure) {
            throw refusal(failure, false);
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
    }

    @Override
    public void registerReplacing(String author, String publicKey, String replacedFingerprint) {
        OwnerIdentity owner = actor.current();
        readiness.require();
        try {
            service.registerReplacing(new RegisterCommand(author, publicKey, null), owner, replacedFingerprint);
        } catch (LedgerException failure) {
            throw refusal(failure, true);
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
    }

    @Override
    public void revoke(String author, String fingerprint) {
        OwnerIdentity owner = actor.current();
        readiness.require();
        try {
            service.revokeConfirmed(author, fingerprint, owner);
        } catch (LedgerException failure) {
            throw revokeRefusal(failure);
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
    }

    /** The CLI failure for a refusal of {@code revokeConfirmed}: no live key, another fingerprint (the live one is shown). */
    private static FailureException revokeRefusal(LedgerException failure) {
        if (failure.code() == LookupCode.NOT_FOUND) {
            return new FailureException(ErrorCode.NO_LIVE_KEY, failure);
        }
        if (failure instanceof ValidationException invalid && failure.code() == ValidationCode.VALIDATION_FAILED
                && invalid.issues().getFirst().reason() == ValidationReason.MUST_MATCH) {
            return new FailureException(ErrorCode.REVOKE_FINGERPRINT_MISMATCH, failure,
                    invalid.issues().getFirst().reasonArgs().getFirst());
        }
        if (failure.code() == TrustCode.AUTHOR_ID_FORMAT || (failure.code() == ValidationCode.VALIDATION_FAILED
                && failure.args().contains("author"))) {
            return new FailureException(ErrorCode.INVALID_AUTHOR, failure);
        }
        return new FailureException(ErrorCode.LEDGER_REFUSED, failure, failure.code().token());
    }

    /** The CLI failure for a refusal of the ledger; {@code replacing} tells that the owner already gave {@code --replace}. */
    private static FailureException refusal(LedgerException failure, boolean replacing) {
        if (failure.code() == ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED) {
            List<String> args = failure.args();
            // the ledger's arguments: author, fingerprint of the live key, registration time (ISO, UTC)
            return new FailureException(replacing ? ErrorCode.REPLACE_FINGERPRINT_STALE
                    : ErrorCode.KEY_REPLACEMENT_NOT_CONFIRMED, failure, args.get(1), args.get(2));
        }
        if (failure.code() == ConflictCode.DUPLICATE_KEY) {
            return new FailureException(ErrorCode.KEY_IN_USE, failure);
        }
        if (failure.code() == TrustCode.AUTHOR_ID_FORMAT || (failure.code() == ValidationCode.VALIDATION_FAILED
                && failure.args().contains("author"))) {
            return new FailureException(ErrorCode.INVALID_AUTHOR, failure);
        }
        return new FailureException(ErrorCode.LEDGER_REFUSED, failure, failure.code().token());
    }
}
