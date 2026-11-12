package org.pgledger.application.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.Tokens;
import org.pgledger.domain.value.UtcInstant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session grants, used by the admin web and the CLI (never by the MCP tools). A grant is a row per scope; granting
 * closes the live grant of the same key first and rows are never deleted. Two grants of one key at the same time
 * end with exactly one live row. An author without a live session key is refused (SESSION_KEY_REQUIRED): the rule is
 * here, once, for every caller.
 */
@Service
@Transactional
public class GrantService {

    /** Who may do what: author, project, scopes (tokens), optional expiry (ISO-8601 with Z or an offset), note. */
    public record GrantCommand(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    /** What to revoke: all live grants of an author, narrowed by project and, with a project, by scope. */
    public record RevokeCommand(String author, String project, String scope) {
    }

    private final GrantStateRepository state;
    private final SessionGrantRepository grants;
    private final SessionKeyStateRepository keys;
    private final ProjectRepository projects;
    private final IdAllocator ids;
    private final OwnerAuditWriter audit;
    private final Clock clock;

    public GrantService(GrantStateRepository state, SessionGrantRepository grants, SessionKeyStateRepository keys,
            ProjectRepository projects, IdAllocator ids, OwnerAuditWriter audit, Clock clock) {
        this.state = state;
        this.keys = keys;
        this.grants = grants;
        this.projects = projects;
        this.ids = ids;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Issues the grants and returns the id of each, one per scope in the order given; who issued them is recorded in the
     * owner's audit trail in the same transaction. Another grant of the same key that commits first makes the insert a
     * duplicate key; the whole call is then retried in a new transaction (the retry is the outer advice, the
     * transaction the inner one), which closes that grant first.
     */
    @Retryable(includes = DuplicateKeyException.class, maxRetries = 3, delay = 10)
    public List<Long> grant(GrantCommand command, OwnerIdentity actor) {
        return issueAll(command, actor);
    }

    private List<Long> issueAll(GrantCommand command, OwnerIdentity actor) {
        AuthorId author = Authors.of(command.author());
        ProjectId project = ProjectId.of(command.project());
        if (!projects.existsById(project)) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", project.value()));
        }
        if (command.scopes() == null || command.scopes().isEmpty()) {
            throw ValidationException.invalid("scopes", ValidationReason.MUST_NAME_A_SCOPE,
                    Tokens.allowed(Scope.values()));
        }
        Set<Scope> scopes = new LinkedHashSet<>();
        command.scopes().forEach(token -> scopes.add(Tokens.parse("scopes", token, Scope.values())));
        Instant expiresAt = command.expiresAt() == null || command.expiresAt().isBlank() ? null
                : UtcInstant.parse("expires_at", command.expiresAt()).value();
        requireLiveKey(author);
        Instant now = clock.instant();
        List<Long> issued = new ArrayList<>();
        for (Scope scope : scopes) {
            issued.add(issue(author, project, scope, now, expiresAt, command.note()));
        }
        audit.record(actor, OwnerAction.GRANT_ISSUED, author.value() + "|" + project.value(),
                "scopes=" + scopes.stream().map(Scope::token).collect(Collectors.joining(",")) + " expires="
                        + (expiresAt == null ? "never" : expiresAt) + " grants="
                        + issued.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return issued;
    }

    /**
     * A write is accepted only when it is signed with the registered key of its author, so a grant for an author without
     * a live key could never be used: it is refused (SESSION_KEY_REQUIRED) before any row is written. The live key is
     * held until this transaction ends, so a close of the key waits for the grant and cannot slip in between.
     */
    private void requireLiveKey(AuthorId author) {
        boolean live = keys.findLive(author).map(key -> keys.holdLive(key.id())).orElse(false);
        if (!live) {
            throw new ConflictException(ConflictCode.SESSION_KEY_REQUIRED, List.of(author.value()));
        }
    }

    private long issue(AuthorId author,ProjectId project, Scope scope, Instant now, Instant expiresAt,
            String note) {
        closeLive(author, project, scope, now);
        long id = ids.next(Counter.SESSION_GRANT);
        grants.insert(SessionGrant.issue(id, author, project, scope, AuthorId.OWNER, now, expiresAt, note));
        return id;
    }

    private void closeLive(AuthorId author, ProjectId project, Scope scope, Instant now) {
        grants.findByActiveKey(SessionGrant.liveKey(author, project, scope))
                .ifPresent(open -> state.revoke(open.id(), now));
    }

    /** Revokes the matching live grants, records who did it in the owner's audit trail, and returns how many. */
    public int revoke(RevokeCommand command, OwnerIdentity actor) {
        return revokeAll(command, actor);
    }

    private int revokeAll(RevokeCommand command, OwnerIdentity actor) {
        AuthorId author = Authors.of(command.author());
        boolean hasProject = command.project() != null && !command.project().isBlank();
        boolean hasScope = command.scope() != null && !command.scope().isBlank();
        if (hasScope && !hasProject) {
            throw ValidationException.invalid("project", ValidationReason.REQUIRED_WITH_SCOPE);
        }
        ProjectId project = hasProject ? ProjectId.of(command.project()) : null;
        Scope scope = hasScope ? Tokens.parse("scope", command.scope(), Scope.values()) : null;
        int revoked = state.revokeLive(author, project, scope, clock.instant());
        audit.record(actor, OwnerAction.GRANT_REVOKED,
                author.value() + (project == null ? "" : "|" + project.value()),
                "scope=" + (scope == null ? "all" : scope.token()) + " revoked=" + revoked);
        return revoked;
    }

    /** The live grants of an author now. */
    @Transactional(readOnly = true)
    public List<SessionGrant> live(String author) {
        return state.findLive(Authors.of(author), clock.instant());
    }

    /** The live grants of every author now, ordered by author, project and scope (the list of the admin web). */
    @Transactional(readOnly = true)
    public List<SessionGrant> liveAll() {
        return state.findAllLive(clock.instant());
    }
}
