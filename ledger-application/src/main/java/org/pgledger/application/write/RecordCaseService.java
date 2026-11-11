package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.RecordCaseResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Record an observed case of a defect through the author channel. */
@Service
@Transactional
public class RecordCaseService {

    private final RecordCaseOperation operation;

    public RecordCaseService(RecordCaseOperation operation) {
        this.operation = operation;
    }

    public RecordCaseResult recordCase(String actor, RecordCaseCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
