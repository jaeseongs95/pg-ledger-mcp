package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.ReceiptOrigin;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.Sha256;

/**
 * Idempotency record of a write request. It is inserted first, in the transaction of the write, with no
 * response; the response is filled in before commit, so a committed receipt always has one. A LEGACY receipt was
 * imported and is never replayed.
 */
@Table("request_receipt")
public record Receipt(@Id RequestId requestId, ReceiptOperation operation, Sha256 payloadSha256, ActorId authorId,
        String login, JsonDocument response, ReceiptOrigin origin, Instant createdAt) {

    /** A reservation made by this ledger; {@code response} is not known yet. */
    public static Receipt reserve(RequestId id, ReceiptOperation operation, Sha256 payload, ActorId author,
            Channel channel, Instant now) {
        return new Receipt(id, operation, payload, author, channel.login(), null, ReceiptOrigin.NATIVE, now);
    }
}
