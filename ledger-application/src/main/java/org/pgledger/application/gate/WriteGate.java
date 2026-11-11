package org.pgledger.application.gate;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.rule.Channel;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.springframework.stereotype.Component;

/**
 * Decides whether a write may start. The order is fixed: a verification through the author channel, the host
 * attestation (present; a signed token with an author of the right format; the live key of the author; the
 * signature; the tool and request id of the call; the time window), the project, the grant. The attestation is
 * checked by the {@link AttestationVerifier}: nothing is looked up for a caller that cannot prove its author, and the
 * grant is only asked for the author the signature proves. The grant check is a conditional UPDATE in the
 * transaction of the write: it affects one row only for a live grant, stamps its last use, and keeps the row
 * locked until commit, so a revocation waits for the write and a committed revocation fails the check.
 */
@Component
public class WriteGate {

    private final AttestationVerifier attestation;
    private final GrantStateRepository grants;
    private final Clock clock;

    public WriteGate(AttestationVerifier attestation, GrantStateRepository grants, Clock clock) {
        this.attestation = attestation;
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * Runs the checks and returns the trusted session.
     *
     * @param actor     the host attestation as the caller sent it
     * @param requestId the request id the attestation must be bound to (the request id of the call; of a batch, the one
     *                  of the whole batch), as the caller sent it
     */
    public GateSession open(String actor, String requestId, Channel channel, Lookup lookup) {
        // a verification through the author channel is refused first, so it is looked up first (once)
        Resolution early = channel != Channel.REVIEWER && lookup.mayVerify() ? lookup.find().get() : null;
        if (early instanceof Resolution.Resolved resolved) {
            requireReviewerForVerification(channel, resolved.targets());
        }
        AuthorId author = attestation.verify(actor, ToolScope.current(), requestId);
        Resolution resolution = early != null ? early : lookup.find().get();
        return switch (resolution) {
            case Resolution.Unresolved unresolved -> throw unresolved.failure();
            case Resolution.Resolved resolved -> {
                if (early == null) {
                    requireReviewerForVerification(channel, resolved.targets());
                }
                Set<Target> distinct = new LinkedHashSet<>(resolved.targets());
                // one fixed order of grant rows, so two writes that need the same grants cannot wait for each other
                distinct.stream().sorted(Comparator.comparing((Target t) -> t.project().value())
                        .thenComparing(t -> t.scope())).forEach(target -> authorize(author, target));
                yield new GateSession(author, channel);
            }
        };
    }

    private static void requireReviewerForVerification(Channel channel, List<Target> targets) {
        if (channel != Channel.REVIEWER && targets.stream().anyMatch(t -> t.scope() == Scope.VERIFY)) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
    }

    /** Checks (and stamps) one more grant of an author that already passed the gate. */
    public void authorize(AuthorId author, Target target) {
        if (!grants.markUsed(author, target.project(), target.scope(), clock.instant())) {
            throw new TrustException(TrustCode.SESSION_NOT_GRANTED,
                    List.of(author.value(), target.scope().token(), target.project().value()),
                    Map.of("author", List.of(author.value()), "scope", List.of(target.scope().token()),
                            "project", List.of(target.project().value())));
        }
    }
}
