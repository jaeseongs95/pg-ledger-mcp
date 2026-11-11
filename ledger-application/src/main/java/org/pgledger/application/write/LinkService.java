package org.pgledger.application.write;

import org.pgledger.application.write.WriteResult.LinkResult;
import org.pgledger.domain.rule.Channel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Link or unlink two entities through the author channel. */
@Service
@Transactional
public class LinkService {

    private final LinkOperation operation;

    public LinkService(LinkOperation operation) {
        this.operation = operation;
    }

    public LinkResult link(String actor, LinkCommand command) {
        return operation.run(actor, Channel.AUTHOR, command);
    }
}
