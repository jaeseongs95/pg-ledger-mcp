package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.CorrectResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Correct a history row through the author channel (a verification is corrected through the review service). */
@Service
@Transactional
public class CorrectService {

    private final CorrectOperation operation;

    public CorrectService(CorrectOperation operation) {
        this.operation = operation;
    }

    public CorrectResult correct(String actor, CorrectCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
