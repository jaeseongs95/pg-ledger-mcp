package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.pgledger.application.gate.AttestationVerifier;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.value.AuthorId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What a declared author may do right now (read only). */
@Service
@Transactional(readOnly = true)
public class WhoamiService {

    /** One live grant. */
    public record GrantView(String project, String scope, Instant expiresAt) {
    }

    /** The author, whether its format is valid, whether the channel can verify, the login and the live grants. */
    public record Whoami(String author, boolean authorFormatOk, boolean canVerifyLogin, String login,
            List<GrantView> grants) {

        public Whoami {
            grants = List.copyOf(grants);
        }
    }

    private final AttestationVerifier attestation;
    private final GrantStateRepository grants;
    private final Clock clock;

    public WhoamiService(AttestationVerifier attestation, GrantStateRepository grants, Clock clock) {
        this.attestation = attestation;
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * @param actor the host attestation; null is HOST_ATTESTATION_REQUIRED. A whoami call carries no request id, so
     *        the attestation must have been signed with an empty one. A value whose author has no valid format is
     *        answered with {@code authorFormatOk=false} and no grants, as the old function did; every other
     *        failure of the attestation (unsigned, unknown key, bad signature, wrong tool, outside the window) is
     *        the error of the gate, so a session finds out here why it cannot write.
     */
    public Whoami whoami(String actor, Channel channel) {
        AuthorId author;
        try {
            author = attestation.verify(actor, ToolScope.current(), "");
        } catch (TrustException failure) {
            if (failure.code() == TrustCode.AUTHOR_ID_FORMAT) {
                return new Whoami(failure.args().getFirst(), false, channel == Channel.REVIEWER, channel.login(),
                        List.of());
            }
            throw failure;
        }
        List<GrantView> live = grants.findLive(author, clock.instant()).stream().map(WhoamiService::view).toList();
        return new Whoami(author.value(), true, channel == Channel.REVIEWER, channel.login(), live);
    }

    private static GrantView view(SessionGrant grant) {
        return new GrantView(grant.projectId().value(), grant.scope().token(), grant.expiresAt());
    }
}
