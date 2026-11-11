package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Create or edit a defect, task or condition, through the author channel, in one transaction. */
@Service
@Transactional
public class SaveService {

    private final SaveOperation operation;

    public SaveService(SaveOperation operation) {
        this.operation = operation;
    }

    /** {@code actor} is the host attestation as the host sent it; null is HOST_ATTESTATION_REQUIRED. */
    public SaveResult save(String actor, SaveCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
