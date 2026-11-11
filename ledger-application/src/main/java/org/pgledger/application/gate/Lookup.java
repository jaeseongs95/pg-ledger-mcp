package org.pgledger.application.gate;

import java.util.List;
import java.util.function.Supplier;

/**
 * How the gate finds out what a write touches, and when. A lookup is run once, by the gate.
 *
 * <ul>
 * <li>{@link #afterActor}: the write can never be a verification, so nothing is looked up before the actor is
 *     trusted; a failure of the lookup (an unknown or malformed id) is raised from there, in its place of the
 *     order.
 * <li>{@link #verifiable}: the write may be a verification, which the author channel must refuse before anything
 *     else, so the gate looks it up first on that channel. A row that is not there comes back as
 *     {@link Resolution.Unresolved} and is raised only after the actor is trusted.
 * </ul>
 *
 * @param mayVerify whether the gate must look at the scopes before it checks the actor
 * @param find      the lookup
 */
public record Lookup(boolean mayVerify, Supplier<Resolution> find) {

    public static Lookup afterActor(Supplier<List<Target>> targets) {
        return new Lookup(false, () -> new Resolution.Resolved(targets.get()));
    }

    public static Lookup verifiable(Supplier<Resolution> find) {
        return new Lookup(true, find);
    }
}
