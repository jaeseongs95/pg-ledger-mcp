package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.mcp.error.ToolError;
import org.pgledger.mcp.support.Args;
import org.pgledger.mcp.support.Calls;
import org.pgledger.mcp.support.Calls.Fields;
import org.pgledger.mcp.support.Ledger;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.Scenario;
import org.pgledger.mcp.tool.ReadOutputs;
import org.pgledger.mcp.tool.WriteOutputs;

/**
 * MCW-PRN-01: the {@code fields} of {@code ledger_save} and of a {@code save} operation of {@code ledger_batch} are one
 * input record whose fields are patches. A key written with {@code null} clears an optional field, a key left out is not
 * touched, and the receipts, the history and the error paths are those of the map this record replaced.
 */
@McpServerTest
class SaveInputTest {

    private static ReadOutputs.Defect defect(McpSession session, String id) {
        return session.ok("ledger_defect", new Calls.IdOnly(id), ReadOutputs.Defect.class);
    }

    private static WriteOutputs.Saved createWithOwner(McpSession session, Scenario scenario) {
        Fields withOwner = new Fields(scenario.projectId(), "owned", "ARC", "P2", "behavior", "cause", Scenario.AUTHOR,
                null, null, null, null, null, null, null, null);
        return session.ok("ledger_save", Calls.Save.create("defect", withOwner, Scenario.AUTHOR),
                WriteOutputs.Saved.class);
    }

    private static Args edit(McpSession session, String id, long version, Object fields) {
        return Args.from(session.mapper(), Calls.Save.edit("defect", id, version, Fields.titled("x"), Scenario.AUTHOR))
                .with("fields", fields);
    }

    /** The {@code fields} object of one key; the value may be null (a key written as null). */
    private static Map<String, Object> one(String key, Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(key, value);
        return fields;
    }

    @Test
    @DisplayName("MCW-PRN-01: a key left out is not touched, a key written with null clears the optional field, and the "
            + "history of the clearing says so")
    void leavingOutAndClearing(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);
        assertThat(defect(session, created.id()).ownerActorId()).isEqualTo(Scenario.AUTHOR);

        WriteOutputs.Saved retitled = session.ok("ledger_save",
                edit(session, created.id(), 1, one("title", "retitled")), WriteOutputs.Saved.class);
        assertThat(defect(session, created.id()).ownerActorId()).as("a key left out is not touched")
                .isEqualTo(Scenario.AUTHOR);

        WriteOutputs.Saved cleared = session.ok("ledger_save",
                edit(session, created.id(), retitled.version(), one("owner_actor_id", null)),
                WriteOutputs.Saved.class);

        ReadOutputs.Defect read = defect(session, created.id());
        assertThat(read.ownerActorId()).as("a key written as null clears the field").isNull();
        assertThat(read.title()).isEqualTo("retitled");
        assertThat(cleared.version()).isEqualTo(3);
        assertThat(Ledger.history(session, scenario.projectId())).extracting(ReadOutputs.History::op)
                .containsExactly("CREATE", "EDIT", "EDIT");
    }

    @Test
    @DisplayName("MCW-PRN-01: a key that belongs to another entity (purpose of a defect) is VALIDATION_FAILED on "
            + "fields.purpose and nothing is written")
    void aKeyOfAnotherEntity(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        ToolError error = session.failed("ledger_save",
                edit(session, created.id(), 1, one("purpose", "not a defect field")));

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("fields.purpose");
        assertThat(defect(session, created.id()).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("MCW-PRN-01: an unknown key in a batch save is VALIDATION_FAILED UNKNOWN_ARGUMENT on "
            + "ops[<i>].fields.<key> (the batch binds fields as the same record as ledger_save), nothing is written")
    void anUnknownKeyInABatch(McpSession session, Scenario scenario) {
        Args call = Args.from(session.mapper(), Calls.Save.create("defect",
                Fields.defect(scenario.projectId(), "t"), Scenario.AUTHOR));
        Args batch = Args.of().with("_hostAttestation", call.values().get("_hostAttestation")).with("ops", List.of(
                Args.of().with("op", "save").with("entity", "defect").with("reason", Scenario.REASON)
                        .with("fields", Args.of().with("title", "t").with("titel", "x"))));

        ToolError error = session.failed("ledger_batch", batch);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.issues()).extracting(ToolError.Issue::path).containsExactly("ops[0].fields.titel");
        assertThat(Ledger.history(session, scenario.projectId())).isEmpty();
    }

    @Test
    @DisplayName("MCW-PRN-01: the old text form of fields still binds, with the same patches (a JSON text with a null "
            + "clears)")
    void theTextFormOfFields(McpSession session, Scenario scenario) {
        WriteOutputs.Saved created = createWithOwner(session, scenario);

        session.ok("ledger_save", edit(session, created.id(), 1, null)
                .with("fields", "{\"title\":\"from text\",\"owner_actor_id\":null}"), WriteOutputs.Saved.class);

        ReadOutputs.Defect read = defect(session, created.id());
        assertThat(read.title()).isEqualTo("from text");
        assertThat(read.ownerActorId()).isNull();
    }
}
