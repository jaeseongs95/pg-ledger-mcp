// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.veridger.application.gate.GateSession;
import org.veridger.application.gate.Lookup;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.receipt.ReceiptService;
import org.veridger.domain.model.ReceiptOperation;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.CanonicalPayload;
import org.veridger.domain.value.Reason;
import org.veridger.domain.value.OperationKey;
import org.veridger.domain.value.Sha256;

/**
 * The fixed shape of every write, in the order of the gate: find out what the request touches, check the actor
 * and the grants (in the transaction of the write), validate the reason, validate and normalise the input, insert
 * the receipt first (a replay returns the stored response here), then apply the business rules and write, and
 * finally store the response in the receipt. The grants of a replay are those of the call that was answered
 * ({@link #targetsAnswered}). Each write is a subclass that fills in the three steps that differ.
 * The class has no transaction of its own: it runs inside the transaction of the service that calls it.
 */
public abstract class WriteOperation<C extends WriteCommand, R extends WriteResult> {

    /** The validated input of a request: what the receipt digest covers, and the work to do once it is the first. */
    public record Prepared<R>(CanonicalPayload.Fields payload, Function<WriteContext, R> execute) {
    }

    private final WriteGate gate;
    private final ReceiptService receipts;
    private final ReceiptOperation operation;
    private final Class<R> resultType;

    protected WriteOperation(WriteGate gate, ReceiptService receipts, ReceiptOperation operation,
            Class<R> resultType) {
        this.gate = gate;
        this.receipts = receipts;
        this.operation = operation;
        this.resultType = resultType;
    }

    /** The projects and scopes the request needs, and when the gate looks them up (see {@link Lookup}). */
    protected abstract Lookup resolve(C command);

    protected Lookup resolve(C command, Channel channel) {
        return resolve(command);
    }

    /** Validates and normalises the input; the work is run only when the receipt is new. */
    protected abstract Prepared<R> prepare(C command);

    /**
     * The targets that the call needed when it was answered, for the replay of an answer that is stored. By default
     * they are the targets the request needs now ({@code now}); an operation whose targets depend on the state of the
     * entities (a transition needs {@code verify} for a verification) derives them from the stored response instead,
     * so that a replay is not refused or answered otherwise because the entity has changed since.
     */
    protected List<Target> targetsAnswered(C command, R stored, List<Target> now) {
        return now;
    }

    /**
     * Runs the write for {@code actor} (the raw host attestation, possibly absent) through {@code channel}. Must be
     * called inside a transaction.
     */
    public final R run(String actor, Channel channel, C command) {
        return run(actor, command.operationKey(), channel, command);
    }

    /**
     * As {@link #run(String, Channel, WriteCommand)}, for an operation of a batch: the attestation of the call is bound
     * to {@code boundOperationKey}, the operation key of the whole batch, while the operation has a key of its own
     * (derived from the key of the batch and its position).
     */
    public final R run(String actor, String boundOperationKey, Channel channel, C command) {
        return run(actor, boundOperationKey, channel, command, null);
    }

    /**
     * Package-private entry for {@link BatchService}: passes {@code batch} through {@link WriteContext} so locks and
     * deferred counter/history flushes obey the global canonical lock order across all operations in the batch.
     */
    final R run(String actor, String boundOperationKey, Channel channel, C command, BatchBuffer batch) {
        AtomicReference<ReceiptService.LoadedReceipt> preloadedReceipt = new AtomicReference<>();
        GateSession session = gate.open(actor, boundOperationKey, channel, resolve(command, channel),
                (author, now) -> {
                    ReceiptService.LoadedReceipt found = receipts.find(author, command.operationKey());
                    preloadedReceipt.set(found);
                    return receipts.answered(found, operation, resultType)
                            .map(stored -> targetsAnswered(command, stored, now));
                },
                batch == null ? null : batch::isAuthorized);
        return execute(session, command, batch, preloadedReceipt.get());
    }

    /** For authenticated owner-console calls made within the corresponding service boundary. */
    final R runAsOwner(C command) {
        return execute(new GateSession(AuthorId.OWNER, Channel.ADMIN), command, null, null);
    }

    private R execute(GateSession session, C command, BatchBuffer batch, ReceiptService.LoadedReceipt loaded) {
        return session.bind(() -> {
            Reason reason = Reason.of(command.reason());
            OperationKey operationKey = OperationKey.parse(command.operationKey());
            Prepared<R> prepared = prepare(command);
            Sha256 digest = CanonicalPayload.digest(operation.name(),
                    prepared.payload().text("reason", reason.text()));
            // a replay returns the stored response; the first call writes and stores its response in the receipt
            ReceiptService.Reservation<R> reservation = loaded != null
                    ? receipts.reserveWithLoaded(operationKey, operation, digest, session.author(), session.channel(),
                            resultType, loaded)
                    : receipts.reserve(operationKey, operation, digest, session.author(), session.channel(),
                            resultType);
            return switch (reservation) {
                case ReceiptService.Reservation.Replayed<R> replayed -> {
                    if (batch != null) {
                        batch.recordReplayed(replayed.response());
                    }
                    yield replayed.response();
                }
                case ReceiptService.Reservation.Reserved<R> reserved -> {
                    R result = prepared.execute().apply(new WriteContext(session, reason, operationKey, batch));
                    if (batch == null) {
                        receipts.finish(operationKey, session.author(), result);
                    } else {
                        batch.recordPendingReceipt(operationKey, session.author(), result);
                    }
                    yield result;
                }
            };
        });
    }
}
