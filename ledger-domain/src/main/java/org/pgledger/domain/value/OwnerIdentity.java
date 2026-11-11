package org.pgledger.domain.value;

/**
 * Who, among the people and tools that act as the owner, made an owner operation (grant, revoke, key registration, code
 * change): the identity the owner signed in with at the admin web, or the identity of the command line tool. It is
 * text for the owner's audit trail ({@code owner_audit.actor}) and is never an {@link AuthorId}: an author is a session
 * that writes the ledger, the owner identity is a person (or a tool) that administers it.
 *
 * @param value 1 to 300 characters without a control character
 */
public record OwnerIdentity(String value) {

    /** The longest identity ({@code owner_audit.actor} is 300 characters). */
    public static final int MAX_LENGTH = 300;

    /** The identity of a caller that has no person behind it (tests, imports); the services never assume it. */
    public static final OwnerIdentity SYSTEM = new OwnerIdentity("system");

    /**
     * @throws IllegalArgumentException when the value is null, blank, longer than {@value #MAX_LENGTH} characters or
     *         has a control character (the caller builds it from a trusted sign-in, so this is a programming error)
     */
    public OwnerIdentity {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("an owner identity is 1 to " + MAX_LENGTH
                    + " characters without control characters");
        }
    }

    public static OwnerIdentity of(String value) {
        return new OwnerIdentity(value);
    }
}
