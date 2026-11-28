// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

/**
 * What an owner operation did; the {@code operation} column of {@code owner_audit} holds the constant name (at most 20
 * characters, matching the DB CHECK constraint).
 */
public enum OwnerAction {
    GRANT_ISSUED,
    GRANT_REVOKED,
    KEY_REGISTERED,
    KEY_REPLACED,
    KEY_REVOKED,
    TOKEN_ISSUED,
    TOKEN_REVOKED,
    CODE_SET,
    PROJECT_ADDED,
    PROJECT_RENAMED,
    PROJECT_EDITED,
    MILESTONE_ARCHIVED,
    MILESTONE_RESTORED,
    DRIVER_REGISTERED,
    IMPORT,
    PASSKEY_CODE_ISSUED,
    PASSKEY_REGISTERED,
    PASSKEY_REVOKED,
    PASSKEY_RESET,
    ATTACHMENT_REKEY,
    ORPHANS_PURGED
}
