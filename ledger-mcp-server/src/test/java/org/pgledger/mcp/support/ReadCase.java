package org.pgledger.mcp.support;

import java.util.List;
import java.util.function.Function;

/** One read tool with the smallest valid arguments for the rows of a {@link Seed}. */
public record ReadCase(String tool, Function<Seed, Object> arguments) {

    @Override
    public String toString() {
        return tool;
    }

    public Object call(Seed seed) {
        return arguments.apply(seed);
    }

    /** The twelve read tools. */
    public static List<ReadCase> all() {
        return List.of(
                new ReadCase("ledger_get", seed -> new Calls.Ids(List.of(seed.defect()))),
                new ReadCase("ledger_defect", seed -> new Calls.IdOnly(seed.defect())),
                new ReadCase("ledger_task", seed -> new Calls.IdOnly(seed.task())),
                new ReadCase("ledger_history", seed -> Calls.History.of(seed.project())),
                new ReadCase("ledger_search", seed -> new Calls.Nothing()),
                new ReadCase("ledger_defects", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_tasks", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_conditions", seed -> new Calls.ProjectOnly(seed.project())),
                new ReadCase("ledger_attention", seed -> new Calls.Nothing()),
                new ReadCase("ledger_stats", seed -> new Calls.Nothing()),
                new ReadCase("ledger_projects", seed -> new Calls.Nothing()),
                new ReadCase("ledger_codes", seed -> new Calls.Nothing()));
    }
}
