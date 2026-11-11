package org.pgledger.domain.model;

import java.time.Instant;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.TextRule;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The public key an author signs its writes with (Ed25519, X.509 DER in standard base64) and when the owner
 * registered it. Keys are never deleted: replacing or revoking a key closes its row. {@code activeKey} and
 * {@code fingerprintKey} are never-null keys under plain UNIQUE constraints: {@code author|live} and the fingerprint
 * while the key is live, {@code revoked:<id>} once it is closed, so an author has at most one live key, one key
 * belongs to at most one author, and any number of closed rows may coexist.
 *
 * <p>The record holds no secret: a public key and its fingerprint are what the owner sees and compares.
 */
@Table("session_key")
public record SessionPublicKey(@Id Long id, AuthorId authorId, String publicKey, String fingerprint,
        AuthorId registeredBy, Instant registeredAt, Instant revokedAt, String note, String activeKey,
        String fingerprintKey) {

    public static String liveKey(AuthorId author) {
        return author.value() + "|live";
    }

    public static String closedKey(long id) {
        return "revoked:" + id;
    }

    /** A new live key. */
    public static SessionPublicKey register(long id, AuthorId author, String publicKey, String fingerprint,
            AuthorId by, Instant now, String note) {
        return new SessionPublicKey(id, author, publicKey, fingerprint, by, now, null,
                TextRule.GRANT_NOTE.optional(note), liveKey(author), fingerprint);
    }
}
