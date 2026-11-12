package org.pgledger.cli.key;

import java.util.function.Consumer;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.database.CliActor;
import org.pgledger.cli.database.LedgerRefusals;
import org.pgledger.cli.database.LedgerRefusals.Context;
import org.pgledger.cli.database.SchemaReadiness;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.dao.DataAccessException;

/**
 * The {@link SessionKeyRegistry} of a CLI that has the ledger database: it calls the application service
 * {@link SessionKeyService}, the same one the admin web uses for its console, and records the owner operation in the
 * audit trail as {@code cli:<name of the process user>} ({@link CliActor}); it also closes a key at the owner's command
 * ({@code key-revoke}). A refusal of the ledger becomes a {@link FailureException} of the CLI ({@link LedgerRefusals}: the
 * ledger code is translated, its arguments are never echoed except the fingerprint and registration time the owner needs
 * to confirm a replacement); no key or secret is ever part of a message. It exists only when the CLI has its database (the
 * configuration {@code CliLedgerConfiguration} imports it under its condition).
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
        run(Context.REGISTER, owner -> service.register(new RegisterCommand(author, publicKey, null), owner));
    }

    @Override
    public void registerReplacing(String author, String publicKey, String replacedFingerprint) {
        run(Context.REPLACE,
                owner -> service.registerReplacing(new RegisterCommand(author, publicKey, null), owner, replacedFingerprint));
    }

    @Override
    public void revoke(String author, String fingerprint) {
        run(Context.REVOKE, owner -> service.revokeConfirmed(author, fingerprint, owner));
    }

    /** The owner, the schema check, then the work; a refusal of the ledger is translated for what the owner was doing. */
    private void run(Context context, Consumer<OwnerIdentity> work) {
        OwnerIdentity owner = actor.current();
        readiness.require();
        try {
            work.accept(owner);
        } catch (LedgerException failure) {
            throw LedgerRefusals.translate(failure, context);
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
    }
}
