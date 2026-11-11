package org.pgledger.domain.value;

/**
 * Who acted. A declared author ({@link AuthorId}) is what every new write carries; rows imported from an older
 * ledger may hold an actor id that does not follow today's format ({@link LegacyActorId}).
 */
public sealed interface ActorId permits AuthorId, LegacyActorId {

    String value();

    default SessionKey sessionKey() {
        return new SessionKey(value());
    }

    /** Reads a stored actor column: today's format becomes an {@link AuthorId}, anything else a {@link LegacyActorId}. */
    static ActorId ofStored(String stored) {
        return AuthorId.matches(stored) ? new AuthorId(stored) : new LegacyActorId(stored);
    }
}
