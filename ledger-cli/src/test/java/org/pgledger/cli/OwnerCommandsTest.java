package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CLI-005 .. CLI-009 and CLI-037: the owner commands {@code project-add}, {@code code-set}, {@code codes}, {@code grant},
 * {@code revoke} and {@code grants} on the real wiring: the real application context (the real services, repositories and
 * transaction manager; no mock) over an embedded H2 whose schema the real migrator built. The tests read the tables the
 * commands wrote.
 */
@ExtendWith({ OutputCaptureExtension.class, CliExtension.class, EnglishMessages.class })
class OwnerCommandsTest {

    private static final String USER = "alice";
    private static final String AUTHOR = "claude:e2e-author";
    private static final String OWNER = "user:owner";

    private static final String PROJECTS = "SELECT id, code, title, next_defect_no, next_task_no, created_by FROM project ORDER BY id";
    private static final String CODES = "SELECT code_group, code, name_ko, name_en, description_ko, description_en, sort_order, "
            + "state, created_by FROM common_code WHERE code_group = 'DEFECT_CATEGORY' ORDER BY code";
    private static final String GRANTS = "SELECT id, author_id, project_id, scope, granted_by, expires_at, revoked_at, note "
            + "FROM session_grant ORDER BY id";
    private static final String AUDIT = "SELECT actor, operation, subject, detail FROM owner_audit ORDER BY id";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;

    @BeforeEach
    void migrate() {
        database = TestLedgerDatabase.migrated();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Outcome run(Cli cli, RecordingOutput output, String... args) {
        return cli.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null, output, args);
    }

    private Outcome run(Cli cli, String... args) {
        return run(cli, new RecordingOutput(), args);
    }

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The owner registers the key of the author, as the grant needs it (the command key-register, real wiring). */
    private void registerKey(Cli cli, String author) {
        KeyPair pair = pair();
        Outcome result = run(cli, "key-register", author, PublicKeys.encode(pair.getPublic()),
                PublicKeys.fingerprint(pair.getPublic()));
        assertThat(result.exitCode()).isZero();
    }

    private void addProject(Cli cli) {
        assertThat(run(cli, "project-add", "e2e", "E2E", "End-to-end test project").exitCode()).isZero();
    }

    private static String detailOfFirstRefusal(Outcome result) {
        return result.refusal().orElseThrow().arguments().getFirst();
    }

    // CLI-005 ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-005 project-add <id> <code> <title> adds the project (counters 0, created_by user:owner), prints it and records PROJECT_ADDED with the actor cli:<user>")
    void addsAProject(Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "project-add", "e2e", "E2E", "End-to-end test project");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(output.info()).contains("e2e").contains("E2E").contains("End-to-end test project");
        List<Map<String, Object>> projects = database.rows(PROJECTS);
        assertThat(projects).hasSize(1);
        Map<String, Object> project = projects.getFirst();
        assertThat(project.get("id")).isEqualTo("e2e");
        assertThat(project.get("code")).isEqualTo("E2E");
        assertThat(project.get("title")).isEqualTo("End-to-end test project");
        assertThat(((Number) project.get("next_defect_no")).longValue()).isZero();
        assertThat(((Number) project.get("next_task_no")).longValue()).isZero();
        assertThat(project.get("created_by")).isEqualTo(OWNER);
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("PROJECT_ADDED");
        assertThat(audit.getFirst().get("subject")).isEqualTo("e2e");
        assertThat(audit.getFirst().get("detail")).isEqualTo("code=E2E");
    }

    @Test
    @DisplayName("CLI-005 an invalid id, an invalid code or a title of 201 characters is LEDGER_REFUSED naming VALIDATION_FAILED (exit 1); nothing is stored, no audit row")
    void refusesInvalidProjects(Cli cli, CapturedOutput captured) {
        for (String[] line : List.of(new String[] { "bad id", "E2E", "title" }, new String[] { "e2e", "e2e-lower", "title" },
                new String[] { "e2e", "E2E", "x".repeat(201) })) {
            Outcome result = run(cli, "project-add", line[0], line[1], line[2]);

            assertThat(result.exitCode()).as(String.join(" ", line)).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.LEDGER_REFUSED);
            assertThat(detailOfFirstRefusal(result)).startsWith("VALIDATION_FAILED");
        }
        assertThat(captured.getAll()).contains("VALIDATION_FAILED");
        assertThat(database.rows(PROJECTS)).isEmpty();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-005 a duplicate id or a duplicate code is LEDGER_REFUSED naming DUPLICATE_KEY (exit 1) and leaves one project and one audit row")
    void refusesDuplicateProjects(Cli cli) {
        addProject(cli);

        Outcome sameId = run(cli, "project-add", "e2e", "OTHER", "Another");
        Outcome sameCode = run(cli, "project-add", "other", "E2E", "Another");

        for (Outcome result : List.of(sameId, sameCode)) {
            assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(result.code()).contains(ErrorCode.LEDGER_REFUSED);
            assertThat(detailOfFirstRefusal(result)).startsWith("DUPLICATE_KEY");
        }
        assertThat(database.rows(PROJECTS)).hasSize(1);
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    // CLI-006 / CLI-007 ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-006 code-set creates a code with English texts, sort order and --inactive (state INACTIVE), created_by user:owner, and records CODE_SET as cli:<user>; setting it again to the same thing changes nothing")
    void createsACode(Cli cli) {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "code-set", "DEFECT_CATEGORY", "BUG", "ko-name", "ko-desc", "--name-en=English name",
                "--description-en=English description", "--sort=6", "--inactive");

        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).contains("DEFECT_CATEGORY/BUG").contains("INACTIVE");
        List<Map<String, Object>> codes = database.rows(CODES);
        assertThat(codes).hasSize(1);
        Map<String, Object> code = codes.getFirst();
        assertThat(code.get("name_ko")).isEqualTo("ko-name");
        assertThat(code.get("name_en")).isEqualTo("English name");
        assertThat(code.get("description_ko")).isEqualTo("ko-desc");
        assertThat(code.get("description_en")).isEqualTo("English description");
        assertThat(((Number) code.get("sort_order")).intValue()).isEqualTo(6);
        assertThat(code.get("state")).isEqualTo("INACTIVE");
        assertThat(code.get("created_by")).isEqualTo(OWNER);
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("operation")).isEqualTo("CODE_SET");
        assertThat(audit.getFirst().get("subject")).isEqualTo("DEFECT_CATEGORY/BUG");

        Outcome same = run(cli, "code-set", "DEFECT_CATEGORY", "BUG", "ko-name", "ko-desc", "--name-en=English name",
                "--description-en=English description", "--sort=6", "--inactive");

        assertThat(same.exitCode()).isZero();
        assertThat(database.rows(AUDIT)).hasSize(1);
    }

    @Test
    @DisplayName("CLI-006 code-set updates an existing code: without --inactive it is ACTIVE again, without --sort it keeps its position, the English texts fall back to the Korean ones; the audit trail says changed")
    void updatesACode(Cli cli) {
        assertThat(run(cli, "code-set", "DEFECT_CATEGORY", "BUG", "ko-name", "--sort=6", "--inactive").exitCode()).isZero();

        Outcome result = run(cli, "code-set", "DEFECT_CATEGORY", "BUG", "new-ko-name", "new-desc");

        assertThat(result.exitCode()).isZero();
        Map<String, Object> code = database.rows(CODES).getFirst();
        assertThat(code.get("name_ko")).isEqualTo("new-ko-name");
        assertThat(code.get("name_en")).isEqualTo("new-ko-name");
        assertThat(code.get("description_en")).isEqualTo("new-desc");
        assertThat(code.get("state")).isEqualTo("ACTIVE");
        assertThat(((Number) code.get("sort_order")).intValue()).isEqualTo(6);
        assertThat(database.rows(AUDIT).getLast().get("detail").toString()).startsWith("changed");
    }

    @Test
    @DisplayName("CLI-006 special characters (quotes, a line break, a backslash, text that looks like a placeholder or like @CODES:..@) are stored exactly as given")
    void keepsSpecialCharacters(Cli cli) {
        String name = "say \"hi\" it's a back\\slash ${x} @CODES:DEFECT_CATEGORY@";
        String description = "line 1\nline 2 with 'single' and \"double\" quotes and @CODES:RELATION_KIND@";

        Outcome result = run(cli, "code-set", "DEFECT_CATEGORY", "QUOTE", name, description, "--name-en=" + name,
                "--description-en=" + description);

        assertThat(result.exitCode()).isZero();
        Map<String, Object> code = database.rows(CODES).getFirst();
        assertThat(code.get("name_ko")).isEqualTo(name);
        assertThat(code.get("name_en")).isEqualTo(name);
        assertThat(code.get("description_ko")).isEqualTo(description);
        assertThat(code.get("description_en")).isEqualTo(description);
    }

    @Test
    @DisplayName("CLI-006 an unknown group, an invalid code and a name of 201 characters are LEDGER_REFUSED naming VALIDATION_FAILED (exit 1); nothing is stored")
    void refusesInvalidCodes(Cli cli) {
        for (String[] line : List.of(new String[] { "NO_SUCH_GROUP", "BUG", "n" }, new String[] { "DEFECT_CATEGORY", "1bad", "n" },
                new String[] { "DEFECT_CATEGORY", "BUG", "x".repeat(201) })) {
            Outcome result = run(cli, "code-set", line[0], line[1], line[2]);

            assertThat(result.exitCode()).as(String.join(" ", line)).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(detailOfFirstRefusal(result)).startsWith("VALIDATION_FAILED");
        }
        assertThat(database.rows(CODES)).isEmpty();
        assertThat(database.rows(AUDIT)).isEmpty();
    }

    @Test
    @DisplayName("CLI-007 codes [group] lists code, state, sort order and the names, for one group or for every group; an unknown group is refused")
    void listsCodes(Cli cli) {
        assertThat(run(cli, "code-set", "DEFECT_CATEGORY", "BUG", "ko-name", "--name-en=English name", "--sort=6", "--inactive")
                .exitCode()).isZero();
        RecordingOutput one = new RecordingOutput();
        RecordingOutput every = new RecordingOutput();

        Outcome group = run(cli, one, "codes", "DEFECT_CATEGORY");
        Outcome all = run(cli, every, "codes");

        assertThat(group.exitCode()).isZero();
        assertThat(one.info()).isEqualTo("DEFECT_CATEGORY BUG INACTIVE 6 ko-name / English name");
        assertThat(all.exitCode()).isZero();
        long stored = ((Number) database.rows("SELECT COUNT(*) AS n FROM common_code").getFirst().get("n")).longValue();
        assertThat(every.info().lines().count()).isEqualTo(stored);
        assertThat(every.info()).contains("DEFECT_CATEGORY BUG INACTIVE 6 ko-name / English name");
        Outcome unknown = run(cli, "codes", "NO_SUCH_GROUP");
        assertThat(unknown.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(unknown)).startsWith("VALIDATION_FAILED");
    }

    // CLI-008 / CLI-016 -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-008 grant <author> <project> <scope>... --note --expires issues one live grant per scope (granted_by user:owner); an expiry with an offset is stored as UTC; the owner's audit trail records GRANT_ISSUED as cli:<user>")
    void issuesGrants(Cli cli) {
        addProject(cli);
        registerKey(cli, AUTHOR);
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "grant", AUTHOR, "e2e", "defect", "task", "fix", "verify", "--note=x",
                "--expires=2099-01-01T10:00:00+09:00");

        assertThat(result.exitCode()).isZero();
        assertThat(result.failure()).isNull();
        assertThat(output.info()).contains(AUTHOR).contains("e2e").contains("defect,task,fix,verify");
        List<Map<String, Object>> grants = database.rows(GRANTS);
        assertThat(grants).hasSize(4);
        assertThat(grants).extracting(row -> row.get("scope")).containsExactly("DEFECT", "TASK", "FIX", "VERIFY");
        for (Map<String, Object> grant : grants) {
            assertThat(grant.get("author_id")).isEqualTo(AUTHOR);
            assertThat(grant.get("project_id")).isEqualTo("e2e");
            assertThat(grant.get("granted_by")).isEqualTo(OWNER);
            assertThat(grant.get("revoked_at")).isNull();
            assertThat(grant.get("note")).isEqualTo("x");
            assertThat(String.valueOf(grant.get("expires_at"))).startsWith("2099-01-01 01:00:00");
        }
        Map<String, Object> audit = database.rows(AUDIT).getLast();
        assertThat(audit.get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.get("operation")).isEqualTo("GRANT_ISSUED");
        assertThat(audit.get("subject")).isEqualTo(AUTHOR + "|e2e");
        assertThat(audit.get("detail").toString()).contains("scopes=defect,task,fix,verify");
    }

    @Test
    @DisplayName("CLI-008/CLI-016 an expiry without a zone, or a date only, is refused (VALIDATION_FAILED, exit 1) and nothing is granted; the Z form is accepted")
    void refusesAnExpiryWithoutAZone(Cli cli) {
        addProject(cli);
        registerKey(cli, AUTHOR);

        for (String expires : List.of("2099-01-01T10:00:00", "2099-01-01")) {
            Outcome result = run(cli, "grant", AUTHOR, "e2e", "defect", "--expires=" + expires);

            assertThat(result.exitCode()).as(expires).isEqualTo(ExitStatus.FAILURE.code());
            assertThat(detailOfFirstRefusal(result)).startsWith("VALIDATION_FAILED");
        }
        assertThat(database.rows(GRANTS)).isEmpty();

        Outcome zulu = run(cli, "grant", AUTHOR, "e2e", "defect", "--expires=2099-01-01T10:00:00Z");

        assertThat(zulu.exitCode()).isZero();
        assertThat(String.valueOf(database.rows(GRANTS).getFirst().get("expires_at"))).startsWith("2099-01-01 10:00:00");
    }

    @Test
    @DisplayName("CLI-008 granting again closes the earlier live grant of the same key (the row stays) and leaves exactly one live grant; an unknown project is NOT_FOUND and an unknown scope VALIDATION_FAILED")
    void grantsAgainAndRefuses(Cli cli) {
        addProject(cli);
        registerKey(cli, AUTHOR);
        assertThat(run(cli, "grant", AUTHOR, "e2e", "defect").exitCode()).isZero();

        Outcome again = run(cli, "grant", AUTHOR, "e2e", "defect", "--note=second");
        Outcome noProject = run(cli, "grant", AUTHOR, "nope", "defect");
        Outcome noScope = run(cli, "grant", AUTHOR, "e2e", "bogus");

        assertThat(again.exitCode()).isZero();
        List<Map<String, Object>> grants = database.rows(GRANTS);
        assertThat(grants).hasSize(2);
        assertThat(grants.get(0).get("revoked_at")).isNotNull();
        assertThat(grants.get(1).get("revoked_at")).isNull();
        assertThat(grants.get(1).get("note")).isEqualTo("second");
        assertThat(noProject.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(noProject)).startsWith("NOT_FOUND");
        assertThat(noScope.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(detailOfFirstRefusal(noScope)).startsWith("VALIDATION_FAILED");
        assertThat(database.rows(GRANTS)).hasSize(2);
    }

    @Test
    @DisplayName("CLI-037 grant for an author that has no live session key is NO_SESSION_KEY (exit 1) naming key-register; no grant row and no audit row are written")
    void refusesAGrantWithoutAKey(Cli cli, CapturedOutput captured) {
        addProject(cli);
        int auditBefore = database.rows(AUDIT).size();

        Outcome result = run(cli, "grant", AUTHOR, "e2e", "defect", "task");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.NO_SESSION_KEY);
        assertThat(captured.getAll()).contains("key-register").contains(AUTHOR);
        assertThat(database.rows(GRANTS)).isEmpty();
        assertThat(database.rows(AUDIT)).hasSize(auditBefore);
    }

    @Test
    @DisplayName("CLI-037 a key that the owner closed is not a live key: grant is NO_SESSION_KEY again")
    void refusesAGrantAfterTheKeyWasClosed(Cli cli) {
        addProject(cli);
        KeyPair pair = pair();
        assertThat(run(cli, "key-register", AUTHOR, PublicKeys.encode(pair.getPublic()), PublicKeys.fingerprint(pair.getPublic()))
                .exitCode()).isZero();
        assertThat(run(cli, "key-revoke", AUTHOR, "--fingerprint=" + PublicKeys.fingerprint(pair.getPublic())).exitCode())
                .isZero();

        Outcome result = run(cli, "grant", AUTHOR, "e2e", "defect");

        assertThat(result.code()).contains(ErrorCode.NO_SESSION_KEY);
        assertThat(database.rows(GRANTS)).isEmpty();
    }

    @Test
    @DisplayName("CLI-008 grants [author] lists the live grants (author, project, scope, expiry in UTC with Z or never, note); another author has none")
    void listsGrants(Cli cli) {
        addProject(cli);
        registerKey(cli, AUTHOR);
        assertThat(run(cli, "grant", AUTHOR, "e2e", "defect", "task", "--note=first", "--expires=2099-01-01T10:00:00+09:00")
                .exitCode()).isZero();
        assertThat(run(cli, "grant", AUTHOR, "e2e", "fix").exitCode()).isZero();
        RecordingOutput all = new RecordingOutput();
        RecordingOutput one = new RecordingOutput();
        RecordingOutput none = new RecordingOutput();

        Outcome everyone = run(cli, all, "grants");
        Outcome author = run(cli, one, "grants", AUTHOR);
        Outcome other = run(cli, none, "grants", "claude:other-author");

        assertThat(everyone.exitCode()).isZero();
        assertThat(all.info().lines().toList()).containsExactlyInAnyOrder(
                AUTHOR + " e2e defect expires 2099-01-01T01:00:00Z first",
                AUTHOR + " e2e task expires 2099-01-01T01:00:00Z first",
                AUTHOR + " e2e fix expires never ");
        assertThat(author.exitCode()).isZero();
        assertThat(one.info()).isEqualTo(all.info());
        assertThat(other.exitCode()).isZero();
        assertThat(none.info()).isEqualTo("There are no live grants.");
    }

    @Test
    @DisplayName("CLI-008 revoke <author> [project [scope]] revokes exactly the matching live grants and prints how many (rows stay, revoked_at is set); GRANT_REVOKED is recorded as cli:<user> each time")
    void revokesGrants(Cli cli) {
        addProject(cli);
        registerKey(cli, AUTHOR);
        assertThat(run(cli, "grant", AUTHOR, "e2e", "defect", "task", "fix", "verify").exitCode()).isZero();
        RecordingOutput scope = new RecordingOutput();
        RecordingOutput project = new RecordingOutput();
        RecordingOutput all = new RecordingOutput();

        Outcome oneScope = run(cli, scope, "revoke", AUTHOR, "e2e", "task");
        Outcome oneProject = run(cli, project, "revoke", AUTHOR, "e2e");
        Outcome nothingLeft = run(cli, all, "revoke", AUTHOR);

        assertThat(oneScope.exitCode()).isZero();
        assertThat(scope.info()).isEqualTo("Revoked 1 live grant(s) of " + AUTHOR + ".");
        assertThat(project.info()).isEqualTo("Revoked 3 live grant(s) of " + AUTHOR + ".");
        assertThat(nothingLeft.exitCode()).isZero();
        assertThat(all.info()).isEqualTo("Revoked 0 live grant(s) of " + AUTHOR + ".");
        List<Map<String, Object>> grants = database.rows(GRANTS);
        assertThat(grants).hasSize(4);
        assertThat(grants).allSatisfy(grant -> assertThat(grant.get("revoked_at")).isNotNull());
        List<Map<String, Object>> revocations = database.rows(AUDIT).stream()
                .filter(row -> "GRANT_REVOKED".equals(row.get("operation"))).toList();
        assertThat(revocations).hasSize(3);
        assertThat(revocations).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("cli:" + USER));
    }

    // CLI-009 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CLI-009 changed by cli2 (the CLI also writes owner_audit): every row the commands create is stamped user:owner (project.created_by, common_code.created_by, session_grant.granted_by) and every owner operation has an owner_audit row whose actor is cli:<process user>")
    void rowsAreOwnedByTheOwnerAndAuditedAsTheCli(Cli cli) {
        addProject(cli);
        registerKey(cli, AUTHOR);
        assertThat(run(cli, "code-set", "DEFECT_CATEGORY", "BUG", "ko-name").exitCode()).isZero();
        assertThat(run(cli, "grant", AUTHOR, "e2e", "defect").exitCode()).isZero();
        assertThat(run(cli, "revoke", AUTHOR, "e2e", "defect").exitCode()).isZero();

        assertThat(database.rows(PROJECTS)).allSatisfy(row -> assertThat(row.get("created_by")).isEqualTo(OWNER));
        assertThat(database.rows(CODES)).allSatisfy(row -> assertThat(row.get("created_by")).isEqualTo(OWNER));
        assertThat(database.rows(GRANTS)).allSatisfy(row -> assertThat(row.get("granted_by")).isEqualTo(OWNER));
        List<Map<String, Object>> audit = database.rows(AUDIT);
        assertThat(audit).extracting(row -> row.get("operation")).containsExactly("PROJECT_ADDED", "KEY_REGISTERED",
                "CODE_SET", "GRANT_ISSUED", "GRANT_REVOKED");
        assertThat(audit).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("cli:" + USER));
    }
}
