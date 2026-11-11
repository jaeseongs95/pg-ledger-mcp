package org.pgledger.application.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.database.repository.SessionGrantRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.SessionGrant;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
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
 * end with exactly one live row.
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
    private final ProjectRepository projects;
    private final IdAllocator ids;
    private final Clock clock;

    public GrantService(GrantStateRepository state, SessionGrantRepository grants, ProjectRepository projects,
            IdAllocator ids, Clock clock) {
        this.state = state;
        this.grants = grants;
        this.projects = projects;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Issues the grants and returns the id of each, one per scope in the order given. Another grant of the same key
     * that commits first makes the insert a duplicate key; the whole call is then retried in a new transaction (the
     * retry is the outer advice, the transaction the inner one), which closes that grant first.
     */
    @Retryable(includes = DuplicateKeyException.class, maxRetries = 3, delay = 10)
    public List<Long> grant(GrantCommand command) {
        AuthorId author = author(command.author());
        ProjectId project = ProjectId.of(command.project());
        if (!projects.existsById(project)) {
            throw new LookupException(LookupCode.NOT_FOUND, List.of("project", project.value()));
        }
        if (command.scopes() == null || command.scopes().isEmpty()) {
            throw ValidationException.invalid("scopes", "must name at least one scope",
                    Tokens.allowed(Scope.values()));
        }
        Set<Scope> scopes = new LinkedHashSet<>();
        command.scopes().forEach(token -> scopes.add(Tokens.parse("scopes", token, Scope.values())));
        Instant expiresAt = command.expiresAt() == null || command.expiresAt().isBlank() ? null
                : UtcInstant.parse("expires_at", command.expiresAt()).value();
        Instant now = clock.instant();
        List<Long> issued = new ArrayList<>();
        for (Scope scope : scopes) {
            issued.add(issue(author, project, scope, now, expiresAt, command.note()));
        }
        return issued;
    }

    private long issue(AuthorId author, ProjectId project, Scope scope, Instant now, Instant expiresAt,
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

    /** Revokes the matching live grants and returns how many. */
    public int revoke(RevokeCommand command) {
        AuthorId author = author(command.author());
        boolean hasProject = command.project() != null && !command.project().isBlank();
        boolean hasScope = command.scope() != null && !command.scope().isBlank();
        if (hasScope && !hasProject) {
            throw ValidationException.invalid("project", "is required when a scope is given");
        }
        return state.revokeLive(author, hasProject ? ProjectId.of(command.project()) : null,
                hasScope ? Tokens.parse("scope", command.scope(), Scope.values()) : null, clock.instant());
    }

    /** The live grants of an author now. */
    @Transactional(readOnly = true)
    public List<SessionGrant> live(String author) {
        return state.findLive(author(author), clock.instant());
    }

    private static AuthorId author(String raw) {
        if (raw == null) {
            throw ValidationException.invalid("author", "is required");
        }
        return AuthorId.of(raw);
    }
}
