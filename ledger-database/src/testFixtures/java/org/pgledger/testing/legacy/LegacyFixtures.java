package org.pgledger.testing.legacy;

import static org.pgledger.testing.legacy.LegacyLedger.at;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The data of the legacy ledger for the tests of the import (MIG-024): the canonical categories of
 * {@code category-legacy-fixture.json} (the class path resource next to this class; 14 codes with their Korean text; the
 * English text is what {@code sql/16_common_code.sql} of 1.x (tag v1.1.0) gives the known codes, and the Korean text is the
 * English fallback of a code it does not know), the severities and relation kinds that installation seeds, and the data that
 * the old admin and the old functions wrote for the upgrade rehearsal of {@code tests/e2e.sh} of 1.x (tag v1.1.0) (project {@code old} with the defects {@code OLD-D-0001..3},
 * tasks, conditions, links, re-linked rows, cases, fixes, grants, history, receipts), plus a second project whose numbers are
 * not contiguous. {@link #rich()} holds only what the import can take as it is; the rows the import has to refuse are added
 * one kind at a time ({@link #withEmptyPurpose}, {@link #withLowerCaseIds}).
 */
public final class LegacyFixtures {

    /** The session that wrote the old data. */
    public static final String SEED = "claude:e2e-seed";
    /** The session that re-linked rows. */
    public static final String RELINK = "codex:e2e-relink";
    /** The owner, who created the rows the old functions did not stamp. */
    public static final String OWNER = "user:owner";
    /** A creator that the backfill could not determine. */
    public static final String UNKNOWN = "unknown:legacy";

    /** A category of the canonical list. */
    public record Category(String code, String name, String definition) {
    }

    private static final String CATEGORY_FIXTURE = "category-legacy-fixture.json";

    /** English name and description of the known categories, as {@code sql/16_common_code.sql} of 1.x (v1.1.0) gives them. */
    private static final Map<String, List<String>> ENGLISH = englishTexts();

    private LegacyFixtures() {
    }

    private static Map<String, List<String>> englishTexts() {
        Map<String, List<String>> texts = new LinkedHashMap<>();
        texts.put("ARC", List.of("Structure and dependency direction",
                "The core depends on outer layers or a contract is tied to a concrete implementation (SOLID)."));
        texts.put("CON", List.of("Concurrency and atomicity", "Races, retries, locks or leases cause an incorrect state."));
        texts.put("DAT", List.of("Data loss and recovery", "Data or backups disappear, or recovery material is not retained."));
        texts.put("DENY", List.of("False refusal and excessive blocking",
                "Valid input, calls or states are blocked or judged failures, stopping normal operation (the opposite of GATE)."));
        texts.put("DIAG", List.of("Incorrect diagnosis",
                "Guidance, fixed messages or status displays do not match the actual cause."));
        texts.put("DOC", List.of("Documentation and contract contradictions",
                "Documentation, schemas or descriptions differ from actual behavior."));
        texts.put("EXC", List.of("Error cause suppression or substitution",
                "Swallowed exceptions or substituted causes or codes hide the actual failure from the caller."));
        texts.put("GATE", List.of("Gate bypass and false PASS",
                "Verification, gates or minimum requirements pass without meeting their actual conditions."));
        texts.put("HOST", List.of("Host extensibility",
                "Behavior is missing on a new or different host, or is handled through another host path."));
        texts.put("PKG", List.of("Build and distribution artifacts",
                "Distributions, dist output or package contents differ from the source or are missing."));
        texts.put("RES", List.of("Resource limits", "Workload, size or time limits are absent or do not impose an actual bound."));
        texts.put("SEC", List.of("Authorization and trust boundaries",
                "Authorization, identity or path boundaries are bypassed, or untrusted values are trusted."));
        texts.put("TST", List.of("Test gaps and false passes",
                "Tests fail to detect defects or encode defective behavior as the expected result."));
        texts.put("VND", List.of("Vendor and model name coupling",
                "Behavior depends on preregistered vendor, model or host names, or name literals (SOLID O and D)."));
        return texts;
    }

    /** The canonical categories, read from the class path resource {@code category-legacy-fixture.json} (no working directory is assumed). */
    public static List<Category> categories() {
        try (InputStream stream = LegacyFixtures.class.getResourceAsStream(CATEGORY_FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("the test fixture resource " + CATEGORY_FIXTURE + " is missing");
            }
            return List.of(JsonMapper.builder().build().readValue(stream, Category[].class));
        } catch (IOException e) {
            throw new UncheckedIOException(CATEGORY_FIXTURE + " cannot be read", e);
        }
    }

    /** The English fallback of a category the old installer does not know has the Korean text. */
    public static List<String> englishOf(Category category) {
        return ENGLISH.getOrDefault(category.code(), List.of(category.name(), category.definition()));
    }

    /** A text of exactly {@code length} characters of Korean text, quotes of both kinds and line feeds. */
    public static String koreanText(int length) {
        String unit = "한글 문장입니다 \"큰따옴표\" '작은따옴표' 줄바꿈\n다음 줄 \\ ";
        return unit.repeat(length / unit.length() + 1).substring(0, length);
    }

    /** A legacy ledger with the data of the old installer and the old admin that the import takes as it is. */
    public static LegacyLedger rich() {
        LegacyLedger ledger = LegacyLedger.create();
        projects(ledger);
        commonCodes(ledger);
        tasks(ledger);
        defects(ledger);
        fixesAndCases(ledger);
        links(ledger);
        conditions(ledger);
        grants(ledger);
        history(ledger);
        receipts(ledger);
        return ledger;
    }

    /**
     * Adds the task {@code OLD-T-UNKNOWN} that has no history and empty {@code purpose} and {@code success_condition}
     * (MIG-023; the 1.x columns allowed {@code ''}, the import stores NULL, REH3-IMP-01).
     */
    public static LegacyLedger withEmptyPurpose(LegacyLedger ledger) {
        return ledger.insert("task", "id", "OLD-T-UNKNOWN", "project_id", "old", "title", "No history", "purpose", "",
                "success_condition", "", "state", "PLANNED", "version", 1, "created_at", at("2026-11-01T00:00:00Z"),
                "updated_at", at("2026-11-01T00:00:00Z"), "created_by", UNKNOWN);
    }

    /** Adds a task and a condition whose ids have lower-case letters (MIG-028: the ids of 1.x are kept). */
    public static LegacyLedger withLowerCaseIds(LegacyLedger ledger) {
        ledger.insert("task", "id", "old-t-lower", "project_id", "old", "title", "Lower-case id", "purpose", "p",
                "success_condition", "s", "state", "PLANNED", "version", 1, "created_at", at("2026-11-01T00:00:00Z"),
                "updated_at", at("2026-11-01T00:00:00Z"), "created_by", SEED);
        return ledger.insert("condition", "id", "old-c-lower", "project_id", "old", "release", "old", "code", "CL",
                "condition_text", "lower case", "state", "UNKNOWN", "version", 1, "created_by", SEED);
    }

    private static void plainTask(LegacyLedger l, String id) {
        l.insert("task", "id", id, "project_id", "old", "title", "Task " + id.length(), "purpose", "p", "success_condition",
                "s", "state", "PLANNED", "version", 1, "created_at", at("2026-11-01T00:00:00Z"), "updated_at",
                at("2026-11-01T00:00:00Z"), "created_by", SEED);
    }

    private static void plainCondition(LegacyLedger l, String id, String code) {
        l.insert("condition", "id", id, "project_id", "old", "release", "old", "code", code, "condition_text", "text",
                "state", "UNKNOWN", "version", 1, "created_by", SEED);
    }

    /** Adds tasks {@code OLD-T-a} and {@code OLD-T-A} and conditions {@code old-c} and {@code OLD-C}: ids that differ only in case. */
    public static LegacyLedger withCaseDuplicates(LegacyLedger l) {
        plainTask(l, "OLD-T-a");
        plainTask(l, "OLD-T-A");
        plainCondition(l, "old-c", "CA");
        plainCondition(l, "OLD-C", "CB");
        return l;
    }

    /** The id of a task that is 121 characters long. */
    public static final String TASK_ID_121 = "OLD-" + "x".repeat(117);

    /** Adds a task id with a space, one of 121 characters and a condition id with a tab: ids that no 1.x domain has a use for. */
    public static LegacyLedger withMalformedIds(LegacyLedger l) {
        plainTask(l, "OLD T SPACE");
        plainTask(l, TASK_ID_121);
        plainCondition(l, "OLD\tC", "CT");
        return l;
    }

    /** Adds a condition with {@code release = ''} and a fix with {@code repo = ''}: NOT NULL columns that identify a row. */
    public static LegacyLedger withEmptyIdentifyingTexts(LegacyLedger l) {
        l.insert("condition", "id", "OLD-C-NORELEASE", "project_id", "old", "release", "", "code", "NR", "condition_text",
                "text", "state", "UNKNOWN", "version", 1, "created_by", SEED);
        l.insert("defect_fix", "id", 30, "defect_id", "E2E-D-0007", "repo", "", "commit_sha", "f".repeat(40), "recorded_by",
                SEED, "recorded_at", at("2026-11-06T02:00:00Z"), "imported", false);
        return l;
    }

    /** The ids of the real shapes of {@link #withRealShapes}: the examples of the production ledger of 1.x. */
    public static final String REAL_TASK_A01 = "AGS:A01";
    public static final String REAL_TASK_LOWER_SUFFIX = "AGS:A07-a";
    public static final String REAL_TASK_LONG = "USER_BACKLOG:revision58:task:ledger-storage-reliability-design";
    public static final String REAL_TASK_CHARS = "AGS#9/x.y:z_1";
    public static final String REAL_TASK_120 = "AGS:" + "x".repeat(116);
    public static final String REAL_TASK_EMPTY_BOTH = "AGS:EMPTY-BOTH";
    public static final String REAL_TASK_EMPTY_ONE = "AGS:EMPTY-ONE";
    public static final String REAL_CONDITION_LONG =
            "AGS:condition:critical-local-fixes-once-cutoff-9254f21a-20261026-r01:actual-load-verification";
    public static final String REAL_CONDITION_LOWER = "ags:c-lower";
    public static final String REAL_DEFECT = "AGS-D-0001";
    /** A condition code of 79 characters (the longest of the production ledger; the 2.0 column held 64). */
    public static final String REAL_CODE_79 = "c".repeat(79);
    /** An origin reference of 9408 characters (the longest of the production ledger; {@code origin_ref} is {@code text} in 1.x). */
    public static final String REAL_ORIGIN_REF_9408 = "https://origin.example/" + "o".repeat(9408 - "https://origin.example/".length());
    /** A CI reference of 533 characters (the longest of the production ledger; the 2.0 column held 500). */
    public static final String REAL_CI_REF_533 = "https://ci.example/" + "r".repeat(533 - "https://ci.example/".length());

    /**
     * Adds what the third rehearsal found in the 1.x ledger (REH3-IMP-01): the project {@code ags}; tasks whose ids have lower-case
     * letters and the characters {@code # / . : _ -}, one of 120 characters; two tasks with {@code ''} in {@code purpose} and
     * {@code success_condition}; a defect with {@code ''} in {@code behavior}; a condition with a code of 79 characters and a
     * condition whose id is lower case and whose text is {@code ''}; a fix with a CI reference of 533 characters; and
     * {@code condition_link} rows to tasks and to defects (the legacy table has no link key).
     */
    public static LegacyLedger withRealShapes(LegacyLedger l) {
        l.insert("project", "id", "ags", "code", "AGS", "title", "The ledger of the real shapes", "created_by", OWNER);
        for (String id : List.of(REAL_TASK_A01, REAL_TASK_LOWER_SUFFIX, REAL_TASK_LONG, REAL_TASK_CHARS, REAL_TASK_120)) {
            task(l, id, "ags", "Task " + id.length(), "PLANNED", 1, SEED,
                    REAL_TASK_LONG.equals(id) ? REAL_ORIGIN_REF_9408 : "");
        }
        l.insert("task", "id", REAL_TASK_EMPTY_BOTH, "project_id", "ags", "title", "Empty texts", "purpose", "",
                "success_condition", "", "state", "PLANNED", "version", 1, "created_at", at("2026-11-01T00:00:00Z"),
                "updated_at", at("2026-11-01T00:00:00Z"), "created_by", UNKNOWN);
        l.insert("task", "id", REAL_TASK_EMPTY_ONE, "project_id", "ags", "title", "One empty text", "purpose", "",
                "success_condition", "it is done", "state", "PLANNED", "version", 1, "created_at",
                at("2026-11-01T00:00:00Z"), "updated_at", at("2026-11-01T00:00:00Z"), "created_by", UNKNOWN);
        l.insert("defect", "id", REAL_DEFECT, "project_id", "ags", "title", "A defect without text", "category", "OLDBUG",
                "severity", "P2", "status", "SOURCE_FIXED", "behavior", "", "root_cause", "", "version", 2, "created_at",
                at("2026-11-05T10:00:00Z"), "updated_at", at("2026-11-05T10:00:00Z"), "created_by", SEED);
        l.insert("defect_fix", "id", 20, "defect_id", REAL_DEFECT, "repo", "repo/ags", "commit_sha", "e".repeat(40), "branch",
                "main", "ci_ref", REAL_CI_REF_533, "ci_conclusion", "success", "ci_completed_at",
                at("2026-11-06T01:02:03Z"), "recorded_by", SEED, "recorded_at", at("2026-11-06T01:03:00Z"), "imported", false);
        l.insert("condition", "id", REAL_CONDITION_LONG, "project_id", "ags", "release", "2.0.0", "goal_label",
                "g".repeat(114), "code", REAL_CODE_79, "condition_text", "the load is verified", "state", "UNKNOWN",
                "version", 1, "created_by", SEED);
        l.insert("condition", "id", REAL_CONDITION_LOWER, "project_id", "ags", "release", "2.0.0", "code", "LOW",
                "condition_text", "", "state", "UNKNOWN", "version", 1, "created_by", SEED);
        l.insert("condition_link", "condition_id", REAL_CONDITION_LONG, "task_id", REAL_TASK_A01, "created_by", SEED);
        l.insert("condition_link", "condition_id", REAL_CONDITION_LONG, "task_id", REAL_TASK_CHARS, "created_by", SEED);
        l.insert("condition_link", "condition_id", REAL_CONDITION_LONG, "defect_id", REAL_DEFECT, "created_by", SEED);
        l.insert("condition_link", "condition_id", REAL_CONDITION_LOWER, "task_id", REAL_TASK_LOWER_SUFFIX, "created_by", SEED);
        l.insert("defect_task", "defect_id", REAL_DEFECT, "task_id", REAL_TASK_LOWER_SUFFIX, "created_by", SEED);
        return l;
    }

    // ----------------------------------------------------------------------------------------------- the data

    private static void projects(LegacyLedger l) {
        l.insert("project", "id", "old", "code", "OLD", "title", "Data written before the upgrade", "created_by", OWNER);
        l.insert("project", "id", "e2e", "code", "E2E", "title", "End-to-end project", "created_by", OWNER);
    }

    private static void commonCodes(LegacyLedger l) {
        severity(l, "P1", "치명", "Critical", "사용자 작업 차단 또는 거짓 완료 보고",
                "Blocks the user's work or reports false completion", 1, true);
        severity(l, "P2", "중대", "Major", "우회 가능한 잘못된 결과 또는 작업 손실",
                "Wrong result or lost work with a workaround", 2, true);
        severity(l, "P3", "경미", "Minor", "불편, 비용 또는 문구 문제", "Inconvenience, cost or wording", 3, true);
        severity(l, "UNRATED", "미분류", "Unrated", "기존 행 전용, 다음 변경에 P1/P2/P3 지정 필요",
                "Legacy only; rate P1, P2 or P3 on the next write", 9, false);
        relation(l, "same_site", "동일 위치", "Same site", "코드 또는 제품의 같은 위치", "Same place in the code or product", 1);
        relation(l, "caused_by", "원인 관계", "Caused by", "a의 원인은 b", "a is caused by b", 2);
        relation(l, "blocks", "차단", "Blocks", "a가 b를 차단", "a blocks b", 3);
        relation(l, "same_family", "동일 계열", "Same family", "근본 원인이 다른 유사 결함",
                "Similar defects with different root causes", 4);
        relation(l, "same_fix_unit", "동일 수정 단위", "Same fix unit", "하나의 변경에서 함께 수정", "Fixed together in one change", 5);
        relation(l, "regression_of", "재발(회귀)", "Regression of", "b는 검증까지 끝난 a가 다시 나타난 것(나중 번호가 재발)",
                "b is a recurrence of a after a was verified (the later id is the recurrence)", 6);
        for (Category category : categories()) {
            List<String> english = englishOf(category);
            l.insert("common_code", "code_group", "DEFECT_CATEGORY", "code", category.code(), "name_ko", category.name(),
                    "name_en", english.get(0), "description_ko", category.definition(), "description_en", english.get(1),
                    "sort_order", 0, "active", true, "created_by", OWNER);
        }
        // a category that only the old installation has: its English text is the Korean one (the fallback of 16_common_code.sql)
        l.insert("common_code", "code_group", "DEFECT_CATEGORY", "code", "OLDBUG", "name_ko", "Old bug", "name_en", "Old bug",
                "description_ko", "Category written before the upgrade", "description_en",
                "Category written before the upgrade", "sort_order", 0, "active", true, "created_by", OWNER);
    }

    private static void severity(LegacyLedger l, String code, String ko, String en, String descriptionKo,
            String descriptionEn, int sort, boolean active) {
        l.insert("common_code", "code_group", "DEFECT_SEVERITY", "code", code, "name_ko", ko, "name_en", en,
                "description_ko", descriptionKo, "description_en", descriptionEn, "sort_order", sort, "active", active,
                "created_by", OWNER);
    }

    private static void relation(LegacyLedger l, String code, String ko, String en, String descriptionKo,
            String descriptionEn, int sort) {
        l.insert("common_code", "code_group", "RELATION_KIND", "code", code, "name_ko", ko, "name_en", en,
                "description_ko", descriptionKo, "description_en", descriptionEn, "sort_order", sort, "active", true,
                "created_by", OWNER);
    }

    private static void task(LegacyLedger l, String id, String project, String title, String state, int version,
            String creator, String originRef) {
        l.insert("task", "id", id, "project_id", project, "title", title, "purpose", "purpose of " + title,
                "success_condition", "success of " + title, "state", state, "owner_actor_id", "", "origin_ref", originRef,
                "version", version, "created_at", at("2026-11-02T10:00:00.250000+09:00"), "updated_at",
                at("2026-11-03T10:00:00.750000-05:00"), "created_by", creator);
    }

    private static void tasks(LegacyLedger l) {
        task(l, "OLD-T-1", "old", "Old task", "PLANNED", 1, SEED, "");
        // a task whose id is a defect id: the link keys keep the two kinds apart
        task(l, "OLD-D-0001", "old", "Task alias", "PLANNED", 1, SEED, "https://origin.example/alias");
        task(l, "A|T", "old", "Pipe target", "PLANNED", 1, UNKNOWN, "");
        task(l, "T", "old", "Plain target", "PLANNED", 2, UNKNOWN, "");
        task(l, "E2E-T-0003", "e2e", "Third task", "ACTIVE", 4, SEED, "");
        task(l, "E2E-T-1", "e2e", "Free id task", "PLANNED", 1, SEED, "");
    }

    private static void defect(LegacyLedger l, String id, String project, String title, String status, String mergedInto,
            String severity, int version, String behavior, String creator) {
        l.insert("defect", "id", id, "project_id", project, "title", title, "category", "OLDBUG", "severity", severity,
                "status", status, "merged_into", mergedInto, "behavior", behavior, "root_cause", "cause of " + title,
                "owner_actor_id", null, "version", version, "created_at", at("2026-11-05T10:00:00.123456Z"), "updated_at",
                at("2026-11-05T19:30:00.654321+09:00"), "created_by", creator);
    }

    private static void defects(LegacyLedger l) {
        defect(l, "OLD-D-0001", "old", "First old defect", "OPEN", null, "P2", 1, "old behaviour", SEED);
        defect(l, "OLD-D-0002", "old", "Merged old defect", "MERGED", "OLD-D-0001", "P2", 2, "old merged behaviour", SEED);
        defect(l, "OLD-D-0003", "old", "Unrated defect", "OPEN", null, "UNRATED", 1, "old unrated behaviour", SEED);
        // numbers that are not contiguous; the merged defect has a lower id than its target, and is inserted after it
        defect(l, "E2E-D-0100", "e2e", "Highest defect", "OPEN", null, "P1", 3, koreanText(4000), SEED);
        defect(l, "E2E-D-0004", "e2e", "Merged into a higher id", "MERGED", "E2E-D-0100", "P3", 2, "merged behaviour", SEED);
        defect(l, "E2E-D-0007", "e2e", "Defect with a fix", "SOURCE_FIXED", null, "P2", 2, "needs a fix", SEED);
    }

    private static void fixesAndCases(LegacyLedger l) {
        l.insert("defect_fix", "id", 3, "defect_id", "E2E-D-0007", "repo", "repo/e2e", "commit_sha", "a".repeat(40), "branch",
                "main", "ci_ref", "https://ci.example/run/1", "ci_conclusion", "success", "ci_completed_at",
                at("2026-11-06T01:02:03.456789Z"), "recorded_by", SEED, "recorded_at", at("2026-11-06T01:03:00Z"),
                "imported", false);
        l.insert("defect_fix", "id", 9, "defect_id", "OLD-D-0001", "repo", "repo/old", "commit_sha", "b".repeat(40), "branch",
                "", "recorded_by", "agent:old-1", "recorded_at", at("2026-11-06T02:00:00+09:00"), "imported", true);
        l.insert("defect_case", "id", 2, "defect_id", "OLD-D-0001", "observation", "seen \"twice\"\nwith 'quotes'",
                "evidence", "", "user_quote", "it broke", "observed_at", at("2026-11-04T08:00:00Z"), "recorded_by", SEED,
                "recorded_at", at("2026-11-04T09:00:00Z"));
        l.insert("defect_case", "id", 5, "defect_id", "OLD-D-0002", "observation", koreanText(4000), "recorded_by", RELINK,
                "recorded_at", at("2026-11-04T09:30:00-05:00"));
    }

    private static void links(LegacyLedger l) {
        l.insert("defect_task", "defect_id", "OLD-D-0001", "task_id", "OLD-T-1", "created_by", SEED);
        l.insert("defect_task", "defect_id", "E2E-D-0007", "task_id", "E2E-T-0003", "created_by", OWNER);
        l.insert("defect_relation", "a", "OLD-D-0001", "b", "OLD-D-0002", "kind", "same_site", "created_by", RELINK);
    }

    private static void condition(LegacyLedger l, String id, String code, String state, String creator) {
        l.insert("condition", "id", id, "project_id", "old", "release", "old", "goal_label", null, "code", code,
                "condition_text", "condition " + code, "state", state, "version", 1, "created_by", creator);
    }

    private static void conditions(LegacyLedger l) {
        condition(l, "OLD-C-1", "C1", "UNKNOWN", SEED);
        condition(l, "OLD-C-2", "C2", "MET", RELINK);
        condition(l, "OLD-C-3", "C3", "NOT_MET", UNKNOWN);
        condition(l, "OLD-PIPE-C", "PIPE", "UNKNOWN", UNKNOWN);
        condition(l, "OLD-PIPE-C|A", "PIPEA", "UNKNOWN", UNKNOWN);
        // the links: both kinds with the same target text, re-linked rows, ids that contain the separator
        l.insert("condition_link", "condition_id", "OLD-C-1", "task_id", "OLD-T-1", "created_by", SEED);
        l.insert("condition_link", "condition_id", "OLD-C-1", "task_id", "OLD-D-0001", "created_by", SEED);
        l.insert("condition_link", "condition_id", "OLD-C-1", "defect_id", "OLD-D-0001", "created_by", RELINK);
        l.insert("condition_link", "condition_id", "OLD-C-2", "defect_id", "OLD-D-0001", "created_by", RELINK);
        l.insert("condition_link", "condition_id", "OLD-C-3", "task_id", "OLD-T-1", "created_by", UNKNOWN);
        l.insert("condition_link", "condition_id", "OLD-PIPE-C", "task_id", "A|T", "created_by", UNKNOWN);
        l.insert("condition_link", "condition_id", "OLD-PIPE-C|A", "task_id", "T", "created_by", UNKNOWN);
    }

    private static void grants(LegacyLedger l) {
        l.insert("session_grant", "id", 1, "author_id", SEED, "project", "old", "scope", "defect", "granted_by", OWNER,
                "granted_at", at("2026-11-01T00:00:00Z"), "note", "e2e seed");
        l.insert("session_grant", "id", 2, "author_id", SEED, "project", "old", "scope", "task", "granted_by", OWNER,
                "granted_at", at("2026-11-01T00:00:00Z"));
        l.insert("session_grant", "id", 4, "author_id", RELINK, "project", "old", "scope", "fix", "granted_by", OWNER,
                "granted_at", at("2026-11-01T00:00:00Z"), "revoked_at", at("2026-11-02T00:00:00Z"), "note", "re-link creator");
        l.insert("session_grant", "id", 7, "author_id", "gemini:expired", "project", "old", "scope", "verify", "granted_by",
                OWNER, "granted_at", at("2020-01-01T00:00:00Z"), "expires_at", at("2021-01-01T00:00:00Z"));
        l.insert("session_grant", "id", 8, "author_id", SEED, "project", "e2e", "scope", "defect", "granted_by", OWNER,
                "granted_at", at("2026-11-01T00:00:00Z"), "expires_at", at("2099-01-01T00:00:00Z"));
    }

    private static void history(LegacyLedger l, long id, OffsetDateTime at, String author, String login, String assurance,
            String entity, String entityId, String op, String from, String to, String changes, String reason,
            Long corrects, String legacyRef) {
        l.insert("history", "id", id, "at", at, "author_id", author, "login", login, "actor_assurance", assurance, "entity",
                entity, "entity_id", entityId, "op", op, "from_state", from, "to_state", to, "changes", changes, "reason",
                reason, "evidence_uri", null, "evidence_sha256", null, "corrects_id", corrects, "request_id",
                UUID.nameUUIDFromBytes(("request-" + id).getBytes(StandardCharsets.UTF_8)), "legacy_ref", legacyRef);
    }

    private static void history(LegacyLedger l) {
        String reason = "e2e: seed before the upgrade";
        history(l, 1, at("2026-11-05T10:00:00.123456Z"), SEED, "ledger_author", "DECLARED", "defect", "OLD-D-0001",
                "CREATE", null, null, "{\"title\":[null,\"First old defect\"]}", reason, null, null);
        history(l, 2, at("2026-11-05T19:00:00.654321+09:00"), SEED, "ledger_author", "DECLARED", "defect", "OLD-D-0001",
                "EDIT", null, null, "{\"title\":[\"old\",\"First old defect\"],\"quote\":\"\\\"x\\\"\"}", reason, null, null);
        history(l, 3, at("2026-11-05T05:00:00-05:00"), SEED, "ledger_author", "DECLARED", "defect", "OLD-D-0002",
                "TRANSITION", "OPEN", "MERGED", "{\"status\":[\"OPEN\",\"MERGED\"]}", reason, null, null);
        history(l, 5, at("2026-11-01T01:30:00-04:00"), RELINK, "ledger_reviewer", "DECLARED", "defect", "E2E-D-0007",
                "TRANSITION", "SOURCE_FIXED", "VERIFIED", "{\"status\":[\"SOURCE_FIXED\",\"VERIFIED\"]}", reason, null, null);
        history(l, 8, at("2026-11-01T01:30:00-05:00"), SEED, "ledger_author", "DECLARED", "defect", "OLD-D-0002", "CORRECT",
                null, null, "{}", "e2e: correct the merge note", 3L, null);
        history(l, 13, at("2026-11-05T00:00:00+09:00"), "agent:old-1", "ledger_author", "LEGACY_LOGIN", "defect",
                "OLD-D-0001", "IMPORT", null, null, "{}", "imported from the older ledger", null, "older:1");
        history(l, 21, at("2000-01-01T00:00:00Z"), "legacy:earlier-note", "ledger_author", "LEGACY_UNKNOWN", "task", "OLD-T-1",
                "LEGACY_NOTE", null, null, "{}", "e2e: earlier note is not the creator", null, "e2e:legacy-earlier-note");
        history(l, 34, at("2026-11-05T12:00:00Z"), "legacy:shared", "ledger_author", "LEGACY_SHARED", "condition_link",
                "OLD-C-1|OLD-T-1", "LEGACY_CHANGE", null, null, "{\"kind\":\"condition_task\"}", "e2e: untyped legacy link",
                null, "e2e:legacy-link");
        history(l, 55, at("2026-11-05T12:30:00Z"), SEED, "ledger_author", "DECLARED", "defect_case", "2", "CREATE", null,
                null, "{\"observation\":[null,\"seen\"]}", reason, null, null);
        history(l, 57, at("2026-11-05T12:45:00.000001Z"), SEED, "ledger_author", "DECLARED", "defect_fix", "3", "LINK", null,
                null, "{}", reason, null, null);
    }

    private static void receipts(LegacyLedger l) {
        String sha = "c".repeat(64);
        l.insert("request_receipt", "request_id", UUID.nameUUIDFromBytes("request-1".getBytes(StandardCharsets.UTF_8)),
                "operation", "save", "payload_sha256", sha, "author_id", SEED, "login", "ledger_author", "response",
                "{\"id\":\"OLD-D-0001\",\"version\":1}", "created_at", at("2026-11-05T10:00:00.123456Z"));
        l.insert("request_receipt", "request_id", UUID.nameUUIDFromBytes("request-3".getBytes(StandardCharsets.UTF_8)),
                "operation", "transition", "payload_sha256", sha, "author_id", SEED, "login", "ledger_author", "response",
                "{\"status\":\"MERGED\"}", "created_at", at("2026-11-05T05:00:00-05:00"));
        l.insert("request_receipt", "request_id", UUID.nameUUIDFromBytes("request-pending".getBytes(StandardCharsets.UTF_8)),
                "operation", "record_fix", "payload_sha256", sha, "author_id", RELINK, "login", "ledger_author",
                "created_at", at("2026-11-06T01:03:00Z"));
    }
}
