package org.pgledger.database.repository;

import java.util.Optional;
import org.pgledger.domain.model.Receipt;
import org.pgledger.domain.value.RequestId;
import org.springframework.data.repository.Repository;

/**
 * Request receipts: insert (a duplicate request id raises a duplicate-key exception) and read. The response is
 * filled in by {@link ReceiptCompletionRepository}.
 */
public interface ReceiptRepository extends Repository<Receipt, RequestId>, Insertable<Receipt> {

    Optional<Receipt> findById(RequestId requestId);
}
