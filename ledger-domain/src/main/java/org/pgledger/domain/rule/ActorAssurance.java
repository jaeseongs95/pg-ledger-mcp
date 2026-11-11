package org.pgledger.domain.rule;

/** How well the actor of a history entry is known: DECLARED for every new write, LEGACY_* for imported rows. */
public enum ActorAssurance {
    DECLARED, LEGACY_LOGIN, LEGACY_SHARED, LEGACY_UNKNOWN
}
