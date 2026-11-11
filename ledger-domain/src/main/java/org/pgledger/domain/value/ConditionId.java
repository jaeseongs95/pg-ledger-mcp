package org.pgledger.domain.value;

/** Id of a release condition: free upper-case text ({@link EntityId#PATTERN}), always given by the caller. */
public record ConditionId(String value) {

    public ConditionId {
        EntityId.requireValid("condition_id", value);
    }

    public static ConditionId of(String value) {
        return new ConditionId(value);
    }
}
