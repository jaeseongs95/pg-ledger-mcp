package org.pgledger.domain.value;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;

/** An actor id imported from an older ledger; only the import path and reads of imported rows produce it. */
public record LegacyActorId(String value) implements ActorId {

    public LegacyActorId {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw ValidationException.invalid("actor_id", ValidationReason.LEGACY_ACTOR_ID_LENGTH, "1", "200");
        }
    }
}
