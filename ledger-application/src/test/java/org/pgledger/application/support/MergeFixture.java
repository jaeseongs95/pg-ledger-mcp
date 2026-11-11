package org.pgledger.application.support;

import org.pgledger.application.write.TransitionCommand;
import org.pgledger.application.write.TransitionService;
import org.pgledger.application.write.WriteResult.TransitionResult;

/** Merges a defect into another one through the author channel (the setup of the merge cases). */
public final class MergeFixture {

    private MergeFixture() {
    }

    /** Moves {@code source} (at {@code version}) to MERGED with {@code target} as the merge target. */
    public static TransitionResult merge(Scenario s, String source, long version, String target) {
        return s.bean(TransitionService.class).transition(Scenario.AUTHOR, new TransitionCommand(null, "defect",
                source, version, "MERGED", Scenario.REASON, null, null, target));
    }
}
