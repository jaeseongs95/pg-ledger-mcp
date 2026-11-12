package org.pgledger.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestDatabaseSettings;

/**
 * W7-SYS-01, W7-SYS-02 and W7-CLI-01 (docs/testing/TEST_CASES.md section 25): {@code setup}, the first setup of the ledger
 * database, run on the packaged {@code ledger-cli.jar} as a process against the real DBMS, the way the installers run it: the
 * schema is migrated, the application account is checked (the step of the DBMS that the operator does, {@code GRANT USAGE ON
 * SCHEMA}, is not done yet: exit code 3 and no code added), the operator does it, the next run adds the 14 seed codes and
 * reports equal counts and digests, and a third run reports no change. The steps depend on each other and run in the order of
 * their ids. The layout of the instance is that of the host ({@link InstalledInstance}); nothing that needs root runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SetupSystemTest {

    private static final int PROCESS_SECONDS = 180;
    private static final String PASSWORD_VARIABLE = "LEDGER_JDBC_PASSWORD";
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    @TempDir
    static Path work;

    private TestDatabaseSettings database;
    private OwnerDatabase owner;
    private InstalledInstance instance;
    private Path etc;
    private Path cliJar;
    private Path runs;
    private String schema;

    private List<String> firstDigests;
    private Map<String, String> stateAfterTheFirstSetup;

    @BeforeAll
    void prepare() throws IOException {
        instance = InstalledInstance.on(work);
        etc = instance.etc();
        runs = Files.createDirectories(work.resolve("runs"));
        database = TestDatabaseSettings.fromSystemProperties();
        owner = new OwnerDatabase(database);
        cliJar = Path.of(System.getProperty("ledger.cli.jar"));
        assertThat(cliJar).as("the jar of ledger-cli").isRegularFile();
        // only the name: the schema is made by the product, and the leftover sweep of TestDatabase cleans a name that a killed
        // run leaves
        schema = TestDatabase.shared().reserveSchemaName();
    }

    @AfterAll
    void cleanUp() {
        if (owner != null && schema != null) {
            owner.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @Order(1)
    @DisplayName("W7-CLI-01: install-default answers one line on the standard output and nothing on the standard error; a refused name gives exit 2, nothing on the standard output, and the report on the standard error")
    void installDefaultKeepsTheLogOffTheStandardOutput() throws IOException {
        JavaProcess.Result answer = JavaProcess.run(runs, Map.of(), null, PROCESS_SECONDS, cliJar,
                "--ledger.secrets.home=" + InstalledInstance.slash(work.resolve("nonexistent")), "install-default",
                "instance-name");
        assertThat(answer.exit()).as(answer.streams()).isZero();
        assertThat(answer.out().lines().toList()).as(answer.streams()).hasSize(1);
        assertThat(answer.err()).as(answer.streams()).isEmpty();

        JavaProcess.Result refused = JavaProcess.run(runs, Map.of(), null, PROCESS_SECONDS, cliJar,
                "--ledger.secrets.home=" + InstalledInstance.slash(work.resolve("nonexistent")), "install-default",
                "no-such-name");
        assertThat(refused.exit()).as(refused.streams()).isEqualTo(2);
        assertThat(refused.out()).as(refused.streams()).isEmpty();
        assertThat(refused.err()).as(refused.streams()).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("W7-SYS-01 (1): render-deploy, then driver-register of the PostgreSQL driver jar")
    void renderAndRegister() throws IOException {
        List<String> command = new ArrayList<>(List.of("render-deploy"));
        command.addAll(instance.renderArguments(InstalledInstance.slash(JavaProcess.java()), database.url(), database.appUser(), schema, 8765));
        JavaProcess.Result rendered = cli(null, command.toArray(String[]::new));
        assertThat(rendered.exit()).as(rendered.streams()).isZero();

        Path source = Path.of(System.getProperty("ledger.test.postgres-jar"));
        // the product refuses a driver jar that an account other than its owner can change: the directory and the jar are made
        // writable by their owner alone, as prepare-host would leave them
        Path drivers = Files.createDirectories(etc.resolve("drivers"));
        ownerOnly(drivers);
        Path copy = Files.createFile(drivers.resolve(source.getFileName()));
        ownerOnly(copy);
        Files.write(copy, Files.readAllBytes(source));
        JavaProcess.Result registered = cli(null, "driver-register", "--jar=" + copy, "--class=org.postgresql.Driver");
        assertThat(registered.exit()).as(registered.streams()).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("W7-SYS-01 (2): setup before the operator has let the application account use the schema: exit 3, the schema is migrated, no code is added")
    void setupStopsAtTheOperatorStep() {
        JavaProcess.Result result = setup();

        assertThat(result.exit()).as(result.streams()).isEqualTo(3);
        assertThat(owner.schemaExists(schema)).isTrue();
        assertThat(owner.value("SELECT COUNT(*) FROM " + schema + ".schema_history")).isEqualTo("3");
        assertThat(owner.value("SELECT COUNT(*) FROM " + schema + ".common_code WHERE code_group = 'DEFECT_CATEGORY'"))
                .as("no seed code before the operator step").isEqualTo("0");
    }

    @Test
    @Order(4)
    @DisplayName("W7-SYS-01 (3): after the operator's GRANT USAGE, setup adds the 14 seed codes and reports equal counts and equal digests")
    void setupAfterTheOperatorStep() {
        // the step of the DBMS that the grant template leaves to the operator: the account may look into the schema
        owner.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + database.appUser());

        JavaProcess.Result result = setup();

        assertThat(result.exit()).as(result.streams()).isZero();
        assertThat(result.out()).as(result.streams()).contains("Added 14 seed code(s)").contains(
                "Seed check: 14 codes in the seed, 14 present in the database, 14 equal to the seed, 0 differ from the seed and were kept (changed by the operator or taken from 1.x by import-legacy)");
        firstDigests = digests(result.out());
        assertThat(firstDigests).as("the digest of the seed and of the same codes in the database").hasSize(2);
        assertThat(firstDigests.get(0)).isEqualTo(firstDigests.get(1));
        assertThat(owner.value("SELECT COUNT(*) FROM " + schema + ".common_code WHERE code_group = 'DEFECT_CATEGORY'"))
                .isEqualTo("14");
        stateAfterTheFirstSetup = state();
    }

    @Test
    @Order(5)
    @DisplayName("W7-SYS-02: a second setup reports no change: Added 0, the same report, and the codes, the revision, the audit trail and the history of the schema are as they were")
    void aSecondSetupChangesNothing() {
        assertThat(stateAfterTheFirstSetup).as("W7-SYS-01 ran").isNotNull();

        JavaProcess.Result result = setup();

        assertThat(result.exit()).as(result.streams()).isZero();
        assertThat(result.out()).as(result.streams()).contains("Added 0 seed code(s)").contains(
                "Seed check: 14 codes in the seed, 14 present in the database, 14 equal to the seed, 0 differ from the seed and were kept (changed by the operator or taken from 1.x by import-legacy)");
        assertThat(digests(result.out())).isEqualTo(firstDigests);
        assertThat(state()).isEqualTo(stateAfterTheFirstSetup);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** {@code setup} through the DDL account (its password on the standard input) with the password of the application account in the environment. */
    private JavaProcess.Result setup() {
        String ownerPassword = database.ownerPassword().orElseThrow(() -> new IllegalStateException(
                "the system test needs the password file of the owner (ledger.test.jdbc-password-file): setup reads the "
                        + "password of the owner account from its standard input"));
        return cli(ownerPassword + "\n", "setup", "--owner-account=" + database.user(), "--stdin",
                "--app-account=" + database.appUser());
    }

    /** What a run of setup could change, as text. */
    private Map<String, String> state() {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("common_code", owner.rows("SELECT code_group, code, name_ko, name_en, sort_order, state FROM " + schema
                + ".common_code ORDER BY code_group, code").toString());
        state.put("code_revision", owner.value("SELECT revision FROM " + schema + ".code_revision WHERE id = 1"));
        state.put("owner_audit", owner.value("SELECT COUNT(*) FROM " + schema + ".owner_audit"));
        state.put("schema_history", owner.rows("SELECT version, sha256, outcome FROM " + schema
                + ".schema_history ORDER BY version, applied_at").toString());
        return state;
    }

    private static List<String> digests(String text) {
        Matcher matcher = DIGEST.matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private JavaProcess.Result cli(String stdin, String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.add("--ledger.secrets.home=" + InstalledInstance.slash(etc));
        all.add("--ledger.jdbc.pool-size=2");
        Map<String, String> environment = new LinkedHashMap<>();
        database.appPassword().ifPresent(password -> environment.put(PASSWORD_VARIABLE, password));
        try {
            return JavaProcess.run(runs, environment, stdin, PROCESS_SECONDS, cliJar, all.toArray(String[]::new));
        } catch (IOException failure) {
            throw new IllegalStateException("the process of ledger-cli could not be run", failure);
        }
    }

    /** Makes a file or directory usable by its owner alone on a file system with ACLs (the POSIX permissions of a new file are the product's check otherwise). */
    private static void ownerOnly(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            return;
        }
        Set<AclEntryFlag> flags = Files.isDirectory(path)
                ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) : EnumSet.noneOf(AclEntryFlag.class);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build()));
    }
}
