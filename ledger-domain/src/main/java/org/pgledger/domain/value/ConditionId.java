package org.pgledger.domain.value;

import java.util.Optional;
import org.pgledger.domain.error.ValidationException;

/** Id of a release condition: free text ({@link EntityId#PATTERN}, the ids of 1.x in any case), always given by the caller. */
public record ConditionId(String value) {

    public ConditionId {
        EntityId.requireValid("condition_id", value);
    }

    /** The id of a text, or none when the text is not an id; nothing is thrown. */
    public static Optional<ConditionId> parse(String value) {
        return EntityId.isValid(value) ? Optional.of(new ConditionId(value)) : Optional.empty();
    }

    /** The failure of a text that is not a condition id. */
    public static ValidationException malformed() {
        return EntityId.malformed("condition_id");
    }

    public static ConditionId of(String value) {
        return new ConditionId(value);
    }

    /** The key under the UNIQUE constraint of the table: the id in upper case ({@link EntityId#key}). */
    public String key() {
        return EntityId.key(value);
    }
}
