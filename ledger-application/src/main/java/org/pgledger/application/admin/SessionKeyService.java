package org.pgledger.application.admin;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public keys that authors sign their writes with. Used by the admin web and the CLI, never by the MCP tools: only
 * the owner decides which key belongs to which author (the owner compares the fingerprint with the one the requesting
 * session printed). Registering closes the live key of the author first; rows are never deleted, so the keys an
 * author had stay on record. Two registrations of one author at the same time end with exactly one live key, and a
 * key is live for one author only.
 */
@Service
@Transactional
public class SessionKeyService {

    /** The author and its public key (X.509 DER in base64, as the hook prints it) with an optional note. */
    public record RegisterCommand(String author, String publicKey, String note) {
    }

    /** What was registered: the row, the author and the fingerprint the owner compares. */
    public record Registered(long id, String author, String fingerprint) {
    }

    private final SessionKeyStateRepository state;
    private final SessionKeyRepository keys;
    private final IdAllocator ids;
    private final Clock clock;

    public SessionKeyService(SessionKeyStateRepository state, SessionKeyRepository keys, IdAllocator ids,
            Clock clock) {
        this.state = state;
        this.keys = keys;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Binds the key to the author. Registering the key that is already live for the author changes nothing and answers
     * with the existing row. A key that is live for another author is refused (DUPLICATE_KEY). Another registration of
     * the same author or key that commits first makes the insert a duplicate key; the whole call is then retried in a
     * new transaction (the retry is the outer advice, the transaction the inner one).
     */
    @Retryable(includes = DuplicateKeyException.class, maxRetries = 3, delay = 10)
    public Registered register(RegisterCommand command) {
        AuthorId author = author(command.author());
        PublicKey key = key(command.publicKey());
        String text = PublicKeys.encode(key);
        String fingerprint = PublicKeys.fingerprint(key);
        Optional<SessionPublicKey> holder = state.findLiveByFingerprint(fingerprint);
        if (holder.isPresent()) {
            if (holder.get().authorId().equals(author)) {
                return registered(holder.get());
            }
            throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("public key " + fingerprint));
        }
        Instant now = clock.instant();
        state.findLive(author).ifPresent(open -> state.close(open.id(), now));
        long id = ids.next(Counter.SESSION_KEY);
        SessionPublicKey row = keys.insert(SessionPublicKey.register(id, author, text, fingerprint, AuthorId.OWNER,
                now, command.note()));
        return registered(row);
    }

    /** Closes the live key of the author; true when there was one. The author cannot write until a key is registered. */
    public boolean revoke(String author) {
        return state.findLive(author(author)).map(open -> state.close(open.id(), clock.instant())).orElse(false);
    }

    /** The live key of the author now. */
    @Transactional(readOnly = true)
    public Optional<SessionPublicKey> live(String author) {
        return state.findLive(author(author));
    }

    /** Every key row of the author, the live one and the closed ones, oldest first. */
    @Transactional(readOnly = true)
    public List<SessionPublicKey> history(String author) {
        return state.findHistory(author(author));
    }

    private static Registered registered(SessionPublicKey row) {
        return new Registered(row.id(), row.authorId().value(), row.fingerprint());
    }

    private static PublicKey key(String text) {
        try {
            return PublicKeys.decode(text);
        } catch (AttestationException failure) {
            throw ValidationException.invalid("public_key", ValidationReason.MUST_BE_ED25519_PUBLIC_KEY, failure);
        }
    }

    private static AuthorId author(String raw) {
        if (raw == null) {
            throw ValidationException.invalid("author", ValidationReason.REQUIRED);
        }
        return AuthorId.of(raw);
    }
}
