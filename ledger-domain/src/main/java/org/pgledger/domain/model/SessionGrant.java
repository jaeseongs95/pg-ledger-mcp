package org.pgledger.domain.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TextRule;

/**
 * Permission of one author to write one scope of one project. Grants are never deleted. {@code activeKey} is a
 * never-null key under a plain UNIQUE constraint: {@code author|project|scope} while live, {@code revoked:<id>}
 * once revoked, so a live grant exists at most once per key and any number of revoked ones may coexist.
 */
@Table("session_grant")
public record SessionGrant(@Id Long id, AuthorId authorId, ProjectId projectId, Scope scope, AuthorId grantedBy,
        Instant grantedAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt, String note, String activeKey) {

    public SessionGrant {
        if (expiresAt != null && !expiresAt.isAfter(grantedAt)) {
            throw ValidationException.invalid("expires_at", "must be after the grant time");
        }
    }

    public static String liveKey(AuthorId author, ProjectId project, Scope scope) {
        return author.value() + "|" + project.value() + "|" + scope.token();
    }

    public static String revokedKey(long id) {
        return "revoked:" + id;
    }

    /** A new live grant. */
    public static SessionGrant issue(long id, AuthorId author, ProjectId project, Scope scope, AuthorId by,
            Instant now, Instant expiresAt, String note) {
        return new SessionGrant(id, author, project, scope, by, now, expiresAt, null, null,
                TextRule.GRANT_NOTE.optional(note), liveKey(author, project, scope));
    }
}
