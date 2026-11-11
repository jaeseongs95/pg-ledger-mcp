package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.TransitionResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Change the state of a defect, task or condition through the author channel (no verifications here). */
@Service
@Transactional
public class TransitionService {

    private final TransitionOperation operation;

    public TransitionService(TransitionOperation operation) {
        this.operation = operation;
    }

    public TransitionResult transition(String actor, TransitionCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
