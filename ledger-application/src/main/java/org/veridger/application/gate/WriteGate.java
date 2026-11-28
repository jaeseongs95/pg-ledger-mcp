// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.gate;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.veridger.database.repository.GrantStateRepository;
import org.veridger.domain.error.TrustCode;
import org.veridger.domain.error.TrustException;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.rule.LockOrder;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.springframework.stereotype.Component;

/**
 * Decides whether a write may start. The order is fixed: a verification through the author channel, the host
 * attestation (present; a signed token with an author of the right format; the signature under the live key of the
 * author, which is read and not locked; the tool, operation key and arguments digest of the call; the time
 * window), the project, the grant. The attestation is checked by the {@link AttestationVerifier}: nothing is looked up
 * for a caller that cannot prove its author, and the grant is only asked for the author the signature proves. The grant
 * of a replay of a call that was answered is the grant of that call ({@link AnsweredCall}), still live: what changed in
 * the entities since (a verification, a move) neither refuses the replay nor changes what it answers, and a revoked
 * grant refuses it.
 *
 * <p>To prevent {@code session_grant} AB-BA deadlocks when movable entities change projects concurrently or grants are
 * revoked/re-issued concurrently, {@link #open} performs a read-only grant liveness pre-check (taking no
 * {@code session_grant} row locks), and each write operation acquires its mutating/movable entity locks first in
 * canonical order ({@code project -> issue -> spec -> task -> milestone_criterion}; read-only issue checks stay
 * compatible with {@code FOR KEY SHARE} / {@code FOR NO KEY UPDATE}) before calling {@link #authorizeAll} (or
 * {@link #authorizeAvailable} in a batch) once in {@link #TARGET_LOCK_ORDER}, which sorts the targets in Java by
 * {@link LockOrder#grant}, the one order of the project and grant locks; the revocation of grants
 * ({@code GrantStateRepository.revokeLive}) sorts its rows with the same comparators, so no path takes the rows in the order
 * of a database {@code ORDER BY}.
 */
@Component
public class WriteGate {

    private static final Comparator<Target> TARGET_LOCK_ORDER = LockOrder.grant(Target::project, Target::scope);

    private final McpCallerResolver callerResolver;
    private final GrantStateRepository grants;
    private final Clock clock;

    public WriteGate(McpCallerResolver callerResolver, GrantStateRepository grants, Clock clock) {
        this.callerResolver = callerResolver;
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * Runs the gate checks (read-only grant pre-check) and returns the trusted session.
     *
     * @param actor        the host attestation or caller token as the caller sent it
     * @param operationKey the operation key the attestation must be bound to (the key of the call; of a batch, the one
     *                     of the whole batch), as the caller sent it
     * @param answered     the targets of the call that this author already got an answer to under the key of the call, if
     *                     any: a replay needs the grants of that call and not those the entities need now (see
     *                     {@link AnsweredCall})
     */
    public GateSession open(String actor, String operationKey, Channel channel, Lookup lookup,
            AnsweredCall answered) {
        return open(actor, operationKey, channel, lookup, answered, null);
    }

    /**
     * Variant of {@link #open(String, String, Channel, Lookup, AnsweredCall)} for batched operations: any target
     * accepted by {@code preAuthorized} was already locked and verified live by {@code BatchLockPlanner} in Phase 1.5,
     * avoiding redundant {@code session_grant} pre-check queries per operation.
     */
    public GateSession open(String actor, String operationKey, Channel channel, Lookup lookup,
            AnsweredCall answered, Predicate<Target> preAuthorized) {
        // a verification through the author channel is refused first, so it is looked up first (once); it is refused
        // here, before the attestation, for a caller that cannot prove its author, and for the author that has no
        // answer to the key (below), so a replay of a call that was answered before the entity was verified goes on
        Optional<Resolution> early = !channel.mayVerify() && lookup.mayVerify() ? Optional.of(lookup.find().get())
                : Optional.empty();
        // outside a tool call there is no tool and no digest: that is the call ToolCall.Outside, which matches nothing signed
        AuthorId author = early.filter(WriteGate::needsVerification).isPresent() ? provenOrRefusedAsVerification(actor,
                operationKey) : callerResolver.verifyForWrite(actor, ToolCall.current(), operationKey);
        Resolution resolution = early.orElseGet(lookup.find());
        return switch (resolution) {
            case Resolution.Unresolved unresolved -> throw unresolved.failure();
            case Resolution.Resolved resolved -> {
                List<Target> needed = answered.targets(author, resolved.targets()).orElse(resolved.targets());
                requireReviewerForVerification(channel, needed);
                Set<Target> distinct = new LinkedHashSet<>(needed);
                distinct.stream().sorted(TARGET_LOCK_ORDER).forEach(target -> {
                    if (preAuthorized == null || !preAuthorized.test(target)) {
                        requireLive(author, target);
                    }
                });
                yield new GateSession(author, channel);
            }
        };
    }

    private static boolean needsVerification(Resolution resolution) {
        return resolution instanceof Resolution.Resolved resolved
                && resolved.targets().stream().anyMatch(t -> t.scope() == Scope.VERIFY);
    }

    /**
     * The author of a call that looks like a verification through the author channel. A caller that cannot prove its
     * author is refused as a verification, as it always was; one that can goes on, and is refused as a verification
     * unless it has an answer to the key of the call (the targets of that answer are checked, not these).
     */
    private AuthorId provenOrRefusedAsVerification(String actor, String operationKey) {
        try {
            return callerResolver.verifyForWrite(actor, ToolCall.current(), operationKey);
        } catch (TrustException unproven) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
    }

    /**
     * Only the caller check of the call, for a write that has to know its author before it looks at a project (the
     * batch reserves the receipt of its key first; each of its operations then passes {@link #open} with its own grants).
     * The checks and their order are those of {@link #open} up to caller verification.
     *
     * @param operationKey the operation key the attestation must be bound to, as the caller sent it
     */
    public AuthorId verifyAuthor(String actor, String operationKey) {
        return callerResolver.verifyForWrite(actor, ToolCall.current(), operationKey);
    }

    /**
     * The gate of a call that touches no project (the upload tools of the attachments): caller identity is checked like
     * for every write, and the author needs at least one live grant.
     */
    public GateSession openWithAnyGrant(String actor, Channel channel) {
        AuthorId author = callerResolver.verifyForWrite(actor, ToolCall.current(), "");
        requireAnyLiveGrant(author);
        return new GateSession(author, channel);
    }

    /** Verifies that {@code author} still holds at least one live grant (used by unsigned capability upload paths). */
    public void requireAnyLiveGrant(AuthorId author) {
        if (grants.findLive(author, clock.instant()).isEmpty()) {
            throw new TrustException(TrustCode.SESSION_NOT_GRANTED, List.of(author.value(), "any", "any"),
                    Map.of("author", List.of(author.value())));
        }
    }

    private static void requireReviewerForVerification(Channel channel, List<Target> targets) {
        if (!channel.mayVerify() && targets.stream().anyMatch(t -> t.scope() == Scope.VERIFY)) {
            throw new TrustException(TrustCode.VERIFY_NEEDS_REVIEWER_LOGIN, List.of());
        }
    }

    /** Read-only liveness check for one grant without locking {@code session_grant}. */
    public void requireLive(AuthorId author, Target target) {
        if (liveGrantTarget(author, target).isEmpty()) {
            throw notGranted(author, target);
        }
    }

    /**
     * Conditionally updates ({@code last_used_at}) and locks every required grant in canonical
     * {@link #TARGET_LOCK_ORDER} ({@link LockOrder}). Call after all entity pins/locks
     * of the operation have been acquired.
     */
    public void authorizeAll(AuthorId author, Collection<Target> targets) {
        Map<Target, Target> required = new LinkedHashMap<>();
        for (Target requested : targets) {
            Target live = liveGrantTarget(author, requested).orElseThrow(() -> notGranted(author, requested));
            required.putIfAbsent(live, requested);
        }
        required.keySet().stream().sorted(TARGET_LOCK_ORDER).forEach(live -> {
            if (!stamp(author, live)) {
                throw notGranted(author, required.get(live));
            }
        });
    }

    /**
     * Conditionally updates ({@code last_used_at}) and locks every live grant among {@code targets} in canonical
     * {@link #TARGET_LOCK_ORDER} ({@link LockOrder}), returning the subset that was
     * authorized. Missing or revoked grants are left out so a multi-operation batch reports
     * {@code SESSION_NOT_GRANTED} at the exact failing operation index.
     */
    public Set<Target> authorizeAvailable(AuthorId author, Collection<Target> targets) {
        Map<Target, Target> requestedToLive = new LinkedHashMap<>();
        for (Target requested : new LinkedHashSet<>(targets)) {
            liveGrantTarget(author, requested).ifPresent(live -> requestedToLive.put(requested, live));
        }
        Set<Target> authorized = new LinkedHashSet<>();
        requestedToLive.values().stream().distinct().sorted(TARGET_LOCK_ORDER)
                .filter(live -> stamp(author, live)).forEach(authorized::add);
        Set<Target> accepted = new LinkedHashSet<>();
        requestedToLive.forEach((requested, live) -> {
            if (authorized.contains(live)) {
                accepted.add(requested);
            }
        });
        return Set.copyOf(accepted);
    }

    /** Checks and stamps the exact permission used by a single-target write. */
    public void authorize(AuthorId author, Target target) {
        authorizeAll(author, List.of(target));
    }

    private Optional<Target> liveGrantTarget(AuthorId author, Target requested) {
        var now = clock.instant();
        if (grants.hasLive(author, requested.project(), requested.scope(), now)) {
            return Optional.of(requested);
        }
        Scope bundle = requested.scope().bundledScope();
        if (bundle != null && grants.hasLive(author, requested.project(), bundle, now)) {
            return Optional.of(new Target(requested.project(), bundle));
        }
        return Optional.empty();
    }

    private boolean stamp(AuthorId author, Target target) {
        return grants.markUsed(author, target.project(), target.scope(), clock.instant());
    }

    private static TrustException notGranted(AuthorId author, Target target) {
        return new TrustException(TrustCode.SESSION_NOT_GRANTED,
                List.of(author.value(), target.scope().token(), target.project().value()),
                Map.of("author", List.of(author.value()), "scope", List.of(target.scope().token()),
                        "project", List.of(target.project().value())));
    }
}
