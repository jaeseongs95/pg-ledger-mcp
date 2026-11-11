package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.BatchResults;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Parallel;
import org.pgledger.mcp.support.Parallel.Outcome;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.WriteOutputs.Saved;
import org.pgledger.mcp.tool.WriteOutputs.Whoami;
import org.springframework.context.ApplicationContext;

/** MCW cases about a call as a whole: it is atomic, and the caller of each call is the caller of that call. */
@McpServerTest
class WriteAtomicityTest {

    private static final int SESSIONS = 20;

    @Test
    @DisplayName("MCW-020: a batch that fails in its second operation rolls back the first, and the error names the "
            + "failing operation")
    void aFailingBatchRollsBackEverything(McpSession session, Scenario scenario) {
        Calls.Batch batch = Calls.Batch.of(Scenario.AUTHOR,
                Calls.Op.save("defect", Fields.defect(scenario.projectId(), "first operation")),
                Calls.Op.transition("defect", scenario.defectId(99), 1, "DISPUTED"));

        ToolError error = session.failed("ledger_batch", batch);

        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.details()).containsEntry("op_index", List.of("2")).containsEntry("op", List.of("transition"));
        assertThat(Ledger.get(session, scenario.defectId(1)).notFound()).containsExactly(scenario.defectId(1));
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    @DisplayName("MCW-020: the batch that was fixed and sent again writes everything once")
    void theCorrectedBatchCommits(McpSession session, Scenario scenario) {
        Calls.Batch batch = Calls.Batch.of(Scenario.AUTHOR,
                Calls.Op.save("defect", Fields.defect(scenario.projectId(), "first operation")),
                Calls.Op.save("task", Fields.task(scenario.projectId(), "second operation")));

        BatchResults results = session.ok("ledger_batch", batch, BatchResults.class);

        assertThat(results.count()).isEqualTo(2);
        assertThat(Ledger.get(session, scenario.defectId(1), scenario.taskId(1)).items()).hasSize(2);
        assertThat(Ledger.history(session, scenario.projectId())).hasSize(2);
    }

    /** What one session of the parallel run saw: the caller it attested and the author of each result. */
    private record Seen(String actor, String whoamiAuthor, String savedAuthor) {
    }

    @Test
    @DisplayName("MCW-021: twenty parallel sessions with different attestations: every result carries its own caller")
    void everyResultCarriesItsOwnCaller(ApplicationContext context, Scenario scenario) {
        List<String> actors = IntStream.range(0, SESSIONS).mapToObj(i -> scenario.actorWith("par" + i, "defect"))
                .toList();
        List<Callable<Seen>> calls = actors.stream().map(actor -> (Callable<Seen>) () -> {
            try (McpSession own = McpSession.open(context)) {
                Whoami who = own.ok("ledger_whoami", Calls.Whoami.as(actor), Whoami.class);
                Saved saved = own.ok("ledger_save",
                        Calls.Save.create("defect", Fields.defect(scenario.projectId(), "by " + actor), actor),
                        Saved.class);
                return new Seen(actor, who.author(), saved.author());
            }
        }).toList();

        List<Outcome<Seen>> outcomes = Parallel.all(calls);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(Outcome::value).toList())
                .allSatisfy(seen -> assertThat(seen.whoamiAuthor()).isEqualTo(seen.actor()))
                .allSatisfy(seen -> assertThat(seen.savedAuthor()).isEqualTo(seen.actor()));
        assertThat(outcomes.stream().map(outcome -> outcome.value().actor()).distinct().count())
                .isEqualTo(SESSIONS);
    }
}
