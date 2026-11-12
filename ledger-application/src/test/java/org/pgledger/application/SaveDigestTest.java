package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.BatchOpInput;
import org.pgledger.application.write.BatchService;
import org.pgledger.application.write.SaveCommand;
import org.pgledger.application.write.SaveService;
import org.pgledger.testing.TestOwners;

/**
 * SVC-DIG-01: the receipt digest of representative saves, pinned. The expected digests were computed on the code before
 * the save input became a record of patches (the input was a map of texts); the same request has to give the same digest
 * after it, because a stored receipt is compared with the digest of a retried request. The project, the ids and the
 * request ids are fixed, so a digest does not depend on the order of the tests or on the project counter.
 */
@LedgerServiceTest
class SaveDigestTest {

    private static final String PROJECT = "digpin";
    private static final String CODE = "DIGP";

    /** The expected digest by request id: what the code before the input records computed. */
    private static final Map<String, String> PINNED = new LinkedHashMap<>();

    private static final String CREATE_DEFECT = "00000000-0000-4000-8000-0000000000a1";
    private static final String CREATE_TASK = "00000000-0000-4000-8000-0000000000a2";
    private static final String CREATE_CONDITION = "00000000-0000-4000-8000-0000000000a3";
    private static final String EDIT_DEFECT = "00000000-0000-4000-8000-0000000000a4";
    private static final String CLEAR_OWNER_NULL = "00000000-0000-4000-8000-0000000000a5";
    private static final String SET_OWNER = "00000000-0000-4000-8000-0000000000aa";
    private static final String CLEAR_OWNER_BLANK = "00000000-0000-4000-8000-0000000000a6";
    private static final String EDIT_TASK_PROJECT = "00000000-0000-4000-8000-0000000000a7";
    private static final String CLEAR_ORIGIN = "00000000-0000-4000-8000-0000000000a8";
    private static final String BATCH_SAVE = "00000000-0000-4000-8000-0000000000a9";

    static {
        PINNED.put(CREATE_DEFECT, "06406bad7cc83a3702801fd9c5c3f55e4e80de7b86110e683acd052c433f48d0");
        PINNED.put(CREATE_TASK, "6f915f51f1678dea88c8011c116466de8cf1550c9623978ce8642cce22dc836a");
        PINNED.put(CREATE_CONDITION, "ebc192cf7ba356d4199fe01a2d5c80d4ba82c71d34dad1cf943408d75cbea034");
        PINNED.put(EDIT_DEFECT, "4b4df05336f191a304aefa80ee0d6f84c3b59b1b76070ee64039d525a0b6784b");
        PINNED.put(CLEAR_OWNER_NULL, "0d8d0ed897559b4bfb7f095be51e513e9c6248689ea16c2f382595f768b2b776");
        PINNED.put(SET_OWNER, "1a3e72092dac52dab01e577fb31e863e8e6bb51f661e664e905c31703993bd0d");
        PINNED.put(CLEAR_OWNER_BLANK, "4f0776383682702dd881f8d9720c6d789a4302863bd0be5340d15073dd655ec0");
        PINNED.put(EDIT_TASK_PROJECT, "1cafddd218d6052c3ff2c3fb75e4d5cf879675cdd4a2382c6d778afab4f3198e");
        PINNED.put(CLEAR_ORIGIN, "a136a3f212243b800c4540f80bc11b7f7ef3d92fd61ead116f4fbd57b9e8691c");
        PINNED.put(BATCH_SAVE, "ea43ac9ed025a2e6c7bf8cf8c6158e4d572f2064289203bf33661e0bd099e789");
    }

    private static Map<String, String> fields(String... keysAndValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return fields;
    }

    private static SaveCommand save(String requestId, String entity, String id, Long version,
            Map<String, String> fields) {
        return new SaveCommand(requestId, entity, id, version, fields, Scenario.REASON);
    }

    @Test
    @DisplayName("SVC-DIG-01: the receipt digest of a save is what it was before the save input became a record")
    void theDigestOfASaveIsPinned(Scenario s) {
        s.bean(ProjectService.class).add(new ProjectCommand(PROJECT, CODE, "digest pin"), TestOwners.OWNER);
        s.bean(GrantService.class).grant(new GrantCommand(Scenario.AUTHOR, PROJECT, List.of("defect", "task"), null,
                null), TestOwners.OWNER);
        SaveService saves = s.bean(SaveService.class);
        Map<String, String> owner = fields("owner_actor_id", null);

        saves.save(Scenario.AUTHOR, save(CREATE_DEFECT, "defect", CODE + "-D-0001", 0L,
                fields("project_id", PROJECT, "title", "digest defect", "category", "ARC", "severity", "P2", "behavior",
                        "what happens", "root_cause", "why", "owner_actor_id", Scenario.AUTHOR)));
        saves.save(Scenario.AUTHOR, save(CREATE_TASK, "task", CODE + "-T-0001", 0L,
                fields("project_id", PROJECT, "title", "digest task", "purpose", "the purpose", "success_condition",
                        "the success", "owner_actor_id", Scenario.AUTHOR, "origin_ref", "ref-1", "origin_quote",
                        "the words")));
        saves.save(Scenario.AUTHOR, save(CREATE_CONDITION, "condition", CODE + "-C-1", 0L,
                fields("project_id", PROJECT, "release", "r1", "goal_label", "goal", "code", "c1", "condition_text",
                        "the condition")));
        saves.save(Scenario.AUTHOR, save(EDIT_DEFECT, "defect", CODE + "-D-0001", 1L,
                fields("title", "edited title", "behavior", "edited behavior")));
        saves.save(Scenario.AUTHOR, save(CLEAR_OWNER_NULL, "defect", CODE + "-D-0001", 2L, owner));
        saves.save(Scenario.AUTHOR, save(SET_OWNER, "defect", CODE + "-D-0001", 3L,
                fields("owner_actor_id", Scenario.AUTHOR)));
        saves.save(Scenario.AUTHOR, save(CLEAR_OWNER_BLANK, "defect", CODE + "-D-0001", 4L,
                fields("owner_actor_id", "   ")));
        saves.save(Scenario.AUTHOR, save(EDIT_TASK_PROJECT, "task", CODE + "-T-0001", 1L,
                fields("project_id", PROJECT, "purpose", "edited purpose")));
        saves.save(Scenario.AUTHOR, save(CLEAR_ORIGIN, "task", CODE + "-T-0001", 2L, fields("origin_ref", null)));
        s.bean(BatchService.class).batch(Scenario.AUTHOR, "00000000-0000-4000-8000-0000000000ff",
                List.of(new BatchOpInput.Save("defect", fields("project_id", PROJECT, "title", "batch defect",
                        "category", "ARC", "severity", "P2", "behavior", "b", "root_cause", "c"), Scenario.REASON,
                        CODE + "-D-0002", 0L, BATCH_SAVE)));

        SoftAssertions digests = new SoftAssertions();
        PINNED.forEach((requestId, expected) -> digests.assertThat(digest(s, requestId)).as(requestId)
                .isEqualTo(expected));
        digests.assertAll();
        assertThat(PINNED).hasSize(10);
    }

    private static String digest(Scenario s, String requestId) {
        return s.ctx().jdbc().sql("SELECT payload_sha256 FROM request_receipt WHERE request_id = :id")
                .param("id", requestId).query(String.class).single();
    }
}
