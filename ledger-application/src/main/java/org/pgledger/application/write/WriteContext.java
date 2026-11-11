package org.pgledger.application.write;

import org.pgledger.application.gate.GateSession;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Reason;
import org.pgledger.domain.value.RequestId;

/** What the body of a write knows: the trusted session, the validated reason and the request id. */
public record WriteContext(GateSession session, Reason reason, RequestId requestId) {

    public AuthorId author() {
        return session.author();
    }

    public String login() {
        return session.channel().login();
    }
}
