package org.pgledger.domain.rule;

/** What an owner operation did; the {@code operation} column of {@code owner_audit} holds the constant name. */
public enum OwnerAction {
    GRANT_ISSUED, GRANT_REVOKED, KEY_REGISTERED, KEY_REPLACED, KEY_REVOKED, CODE_SET, PROJECT_ADDED, DRIVER_REGISTERED
}
