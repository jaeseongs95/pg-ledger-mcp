package org.pgledger.application.receipt;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.application.json.LedgerJson;
import org.pgledger.database.repository.ReceiptCompletionRepository;
import org.pgledger.database.repository.ReceiptRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.model.ReceiptOperation;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.ReceiptOrigin;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Idempotency. The receipt is inserted first, in the transaction of the write, under a savepoint: a duplicate key
 * (the same request id from another or an earlier call) rolls back to the savepoint only, so the transaction
 * stays usable on every DBMS, and the stored response is returned. A concurrent duplicate waits on the key until
 * the first call commits. A failed write rolls its receipt back with everything else, so a retry with a corrected
 * payload is not a conflict.
 */
@Component
public class ReceiptService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptService.class);

    private final ReceiptRepository receipts;
    private final ReceiptCompletionRepository completion;
    private final LedgerJson json;
    private final Clock clock;

    public ReceiptService(ReceiptRepository receipts, ReceiptCompletionRepository completion, LedgerJson json,
            Clock clock) {
        this.receipts = receipts;
        this.completion = completion;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Reserves the request id. Empty: the caller is the first and writes. Present: the same request was answered
     * before, here is its stored response.
     *
     * @throws ConflictException REQUEST_CONFLICT when the id belongs to another operation, payload, author or
     *         channel; REQUEST_ID_LEGACY when it belongs to an imported receipt
     */
    public <R> Optional<R> reserve(RequestId id, ReceiptOperation operation, Sha256 digest, AuthorId author,
            Channel channel, Class<R> responseType) {
        TransactionStatus transaction = TransactionAspectSupport.currentTransactionStatus();
        Object savepoint = transaction.createSavepoint();
        try {
            receipts.insert(Receipt.reserve(id, operation, digest, author, channel, clock.instant()));
            transaction.releaseSavepoint(savepoint);
            return Optional.empty();
        } catch (DuplicateKeyException duplicate) {
            LOG.debug("request id {} is already reserved; the stored receipt decides", id, duplicate);
            transaction.rollbackToSavepoint(savepoint);
            transaction.releaseSavepoint(savepoint);
            return Optional.of(storedResponse(id, operation, digest, author, channel, responseType));
        }
    }

    /** The response stored for a request id that was reserved before; it must be the same request. */
    private <R> R storedResponse(RequestId id, ReceiptOperation operation, Sha256 digest, AuthorId author,
            Channel channel, Class<R> responseType) {
        Receipt stored = receipts.findById(id).orElseThrow(() -> new InfrastructureException(
                InfrastructureCode.INTEGRITY_VIOLATION, List.of("receipt vanished after a duplicate key: " + id)));
        if (stored.origin() == ReceiptOrigin.LEGACY) {
            throw new ConflictException(ConflictCode.REQUEST_ID_LEGACY, List.of(id.toString()));
        }
        boolean same = stored.operation() == operation && stored.payloadSha256().equals(digest)
                && Objects.equals(stored.authorId().value(), author.value())
                && Objects.equals(stored.login(), channel.login());
        if (!same) {
            throw new ConflictException(ConflictCode.REQUEST_CONFLICT, List.of(id.toString()));
        }
        if (stored.response() == null) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("committed receipt without a response: " + id));
        }
        return json.read(stored.response(), responseType);
    }

    /** Stores the response of the first call, once, before the transaction commits. */
    public void finish(RequestId id, Object response) {
        if (!completion.finish(id, json.write(response))) {
            throw new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                    List.of("receipt missing or already finished: " + id));
        }
    }
}
