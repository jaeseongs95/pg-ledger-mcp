package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.RecordFixResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Record a fix commit of a defect through the author channel. */
@Service
@Transactional
public class RecordFixService {

    private final RecordFixOperation operation;

    public RecordFixService(RecordFixOperation operation) {
        this.operation = operation;
    }

    public RecordFixResult recordFix(String actor, RecordFixCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
