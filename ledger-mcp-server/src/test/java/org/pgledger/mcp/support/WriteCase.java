package org.pgledger.mcp.support;

import java.util.List;
import java.util.function.BiFunction;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.tool.WriteOutputs;

/**
 * One write tool with a call that succeeds on the {@link Seed} rows when the actor is attested: the tool, the record
 * of its result and the arguments as a function of the seed and of the attestation (null leaves it out).
 */
public record WriteCase(String tool, Class<?> output, String actor, BiFunction<Seed, String, Object> arguments) {

    @Override
    public String toString() {
        return tool;
    }

    public Object call(Seed seed) {
        return arguments.apply(seed, actor);
    }

    public Object callWithout(Seed seed) {
        return arguments.apply(seed, null);
    }

    /** The ten write tools. */
    public static List<WriteCase> all() {
        return List.of(
                new WriteCase("ledger_save", WriteOutputs.Saved.class, Scenario.AUTHOR, (seed, actor) -> Calls.Save
                        .create("defect", Fields.defect(seed.project(), "another defect"), actor)),
                new WriteCase("ledger_transition", WriteOutputs.Transitioned.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Transition.of("defect", seed.defect(), 1, "DISPUTED", actor)
                                .withEvidence(Scenario.EVIDENCE)),
                new WriteCase("ledger_record_fix", WriteOutputs.FixRecorded.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.RecordFix.of(seed.defect(), "c".repeat(40), actor)),
                new WriteCase("ledger_record_case", WriteOutputs.CaseRecorded.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.RecordCase.of(seed.defect(), "seen again", actor)),
                new WriteCase("ledger_link", WriteOutputs.Linked.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Link.of("defect_task", seed.defect(), seed.task(), actor)),
                new WriteCase("ledger_correct", WriteOutputs.Corrected.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Correct.of(seed.defectHistoryId(), actor)),
                new WriteCase("ledger_batch", BatchResults.class, Scenario.AUTHOR,
                        (seed, actor) -> Calls.Batch.of(actor,
                                Calls.Op.save("defect", Fields.defect(seed.project(), "batched defect")))),
                new WriteCase("ledger_review", WriteOutputs.Transitioned.class, Scenario.REVIEWER,
                        (seed, actor) -> Calls.Review.of("defect", seed.fixed(), 2, "VERIFIED", actor)),
                new WriteCase("ledger_review_correct", WriteOutputs.Corrected.class, Scenario.REVIEWER,
                        (seed, actor) -> Calls.Correct.of(seed.verificationRow(), actor)),
                new WriteCase("ledger_whoami", WriteOutputs.Whoami.class, Scenario.AUTHOR,
                        (seed, actor) -> new Calls.Whoami(actor, null)));
    }
}
