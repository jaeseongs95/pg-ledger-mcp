package org.pgledger.application.write;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.pgledger.database.repository.GrantStateRepository;
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

    private final GrantStateRepository grants;
    private final Clock clock;

    public WhoamiService(GrantStateRepository grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * @param actor the host attestation; null is HOST_ATTESTATION_REQUIRED. A malformed one is answered with
     *        {@code authorFormatOk=false} and no grants, as the old function did.
     */
    public Whoami whoami(String actor, Channel channel) {
        if (actor == null) {
            throw new TrustException(TrustCode.HOST_ATTESTATION_REQUIRED, List.of());
        }
        boolean formatOk = AuthorId.matches(actor);
        List<GrantView> live = formatOk
                ? grants.findLive(AuthorId.of(actor), clock.instant()).stream().map(WhoamiService::view).toList()
                : List.of();
        return new Whoami(actor, formatOk, channel == Channel.REVIEWER, channel.login(), live);
    }

    private static GrantView view(SessionGrant grant) {
        return new GrantView(grant.projectId().value(), grant.scope().token(), grant.expiresAt());
    }
}
