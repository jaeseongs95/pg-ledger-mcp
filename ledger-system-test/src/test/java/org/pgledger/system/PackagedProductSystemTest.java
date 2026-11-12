package org.pgledger.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SYS-001 .. SYS-012 (docs/testing/TEST_CASES.md section 21): the packaged product against the real DBMS along the install
 * path. Every product jar runs as a separate Java process (see {@link JavaProcess} for why); the steps depend on each other
 * and run in the order of their ids, the way an installation does, and one temporary instance home holds what the product
 * writes. The Windows layout of {@code render-deploy} is used, so the class needs drive paths and is skipped without them.
 * The {@code embedded} profile is never activated.
 *
 * <p>Install steps that are not run here are listed in section 21 of the test cases ({@code prepare-host} and
 * {@code seal-host} need an elevated prompt for the ACLs, WinSW and {@code sc.exe} are not run).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PackagedProductSystemTest {

    private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:(?:/[A-Za-z0-9._+-]+)+");
    private static final Pattern SCHEMA_ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");
    private static final String REASON = "a reason that is long enough";
    /** The environment variable of the product that binds to {@code ledger.jdbc.password} (Spring relaxed binding). */
    private static final String PASSWORD_VARIABLE = "LEDGER_JDBC_PASSWORD";
    private static final int PROCESS_SECONDS = 180;

    /** The directory of the whole class (a static field is injected before the {@code @BeforeAll} method). */
    @TempDir
    static Path work;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private TestDatabaseSettings database;
    private OwnerDatabase owner;
    private Path cliJar;
    private Path mcpJar;
    private Path hookJar;
    private Path home;
    private Path etc;
    private Path runs;
    private String schema;
    private String otherSchema;
    private int port;
    private List<String> serverArguments;
    private String token;
    private Process server;
    private McpSyncClient client;

    // the facts of the steps that later steps need
    private String projectId;
    private String author;
    private Path keys;
    private String hostId;
    private String defectId;
    private String taskId;
    private final List<String> sentRequestIds = new ArrayList<>();
    private Map<String, String> defectBeforeEdit;
    private Map<String, String> taskBeforeTransition;
    private List<Map<String, String>> schemaHistoryAfterMigrate;
    private int historyRowsAfterWrites;
    private int receiptsAfterWrites;

    @BeforeAll
    void prepare() throws IOException {
        home = work.resolve("home");
        assumeTrue(DRIVE_PATH.matcher(slash(home)).matches(),
                "render-deploy --platform=windows needs a drive path without spaces for the instance home: " + home);
        etc = home.resolve("etc");
        runs = Files.createDirectories(work.resolve("runs"));
        database = TestDatabaseSettings.fromSystemProperties();
        owner = new OwnerDatabase(database);
        cliJar = jar("ledger.cli.jar");
        mcpJar = jar("ledger.mcp.jar");
        hookJar = jar("ledger.hook.jar");
        // only the names: the schemas are made by the product, and the leftover sweep of TestDatabase cleans a name that a
        // killed run leaves (the shared pool of TestDatabase is not opened here)
        schema = TestDatabase.shared().reserveSchemaName();
        otherSchema = TestDatabase.shared().reserveSchemaName();
        assertThat(SCHEMA_ACCOUNT.matcher(database.appUser()).matches()).as("the application account is a plain name").isTrue();
        port = freePort();
    }

    @AfterAll
    void cleanUp() {
        // also when a step failed: no process and no schema is left (SYS-011 does the same and checks it)
        close();
        if (owner != null && schema != null) {
            for (String name : List.of(schema, otherSchema)) {
                try {
                    owner.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
                } catch (RuntimeException failure) {
                    System.err.println("the schema " + name + " could not be dropped; the next run sweeps it: "
                            + failure.getMessage());
                }
            }
        }
    }

    // ---- the install path ----------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("SYS-001: render-deploy writes the settings and the service definition of an instance")
    void renderDeploy() throws IOException {
        JavaProcess.Result result = cli(false, null, "render-deploy", "--platform=windows", "--home=" + slash(home),
                "--java=" + slash(JavaProcess.java()), "--jdbc-url=" + database.url(),
                "--app-account=" + database.appUser(), "--schema=" + schema, "--mcp-port=" + port);
        assertThat(result.exit()).as(result.streams()).isZero();
        String application = Files.readString(etc.resolve("application.yaml"));
        assertThat(application).contains(database.url()).contains(database.appUser()).contains(schema);
        assertThat(Files.readString(etc.resolve("mcp.yaml"))).contains("port: " + port);
        String service = Files.readString(home.resolve("lib").resolve("pg-ledger-mcp.xml"));
        Matcher arguments = Pattern.compile("<arguments>(.*?)</arguments>").matcher(service);
        assertThat(arguments.find()).as("the arguments of the MCP service in %s", service).isTrue();
        serverArguments = List.of(arguments.group(1).split(" "));
        assertThat(serverArguments).contains("-jar");
        assertThat(serverArguments.getLast()).startsWith("--spring.config.import=").contains("optional:configtree:");
    }

    @Test
    @Order(2)
    @DisplayName("SYS-002: token-generate stores the token of the agents in the secret directory and keeps it")
    void tokenGenerate() throws IOException {
        JavaProcess.Result first = cli(false, null, "token-generate", "ledger.mcp.token");
        assertThat(first.exit()).as(first.streams()).isZero();
        Path file = etc.resolve("secrets").resolve("ledger.mcp.token");
        assertThat(file).isRegularFile();
        token = Files.readString(file, StandardCharsets.UTF_8).strip();
        assertThat(token.length()).isGreaterThanOrEqualTo(16);
        assertThat(first.out() + first.err()).doesNotContain(token);
        JavaProcess.Result again = cli(false, null, "token-generate", "ledger.mcp.token");
        assertThat(again.exit()).as(again.streams()).isZero();
        assertThat(Files.readString(file, StandardCharsets.UTF_8).strip()).isEqualTo(token);
    }

    @Test
    @Order(3)
    @DisplayName("SYS-003: driver-register registers the PostgreSQL driver jar from the driver directory")
    void driverRegister() throws Exception {
        Path source = Path.of(System.getProperty("ledger.test.postgres-jar"));
        // prepare-host puts the driver jar into this directory on a host (it needs an elevated prompt for the ACLs). The
        // product refuses a driver jar that an account other than the process user, the owner or an administrative account
        // can change (FileTrust), and the temporary directory of a Windows user lets other accounts write: so the directory
        // and the jar are made writable by their owner alone, as prepare-host would leave them.
        Path drivers = Files.createDirectories(etc.resolve("drivers"));
        ownerOnly(drivers);
        Path copy = Files.createFile(drivers.resolve(source.getFileName()));
        ownerOnly(copy);
        Files.write(copy, Files.readAllBytes(source));
        JavaProcess.Result result = cli(false, null, "driver-register", "--jar=" + copy, "--class=org.postgresql.Driver");
        assertThat(result.exit()).as(result.streams()).isZero();
        String registration = Files.readString(etc.resolve("jdbc-driver.yaml"));
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy)));
        assertThat(registration).contains("org.postgresql.Driver").containsIgnoringCase(sha256);
    }

    @Test
    @Order(4)
    @DisplayName("SYS-004: migrate as the owner account creates the schema, applies the files and grants the application account")
    void migrateAsOwner() {
        String ownerPassword = database.ownerPassword().orElseThrow(() -> new IllegalStateException(
                "the system test needs the password file of the owner (ledger.test.jdbc-password-file): migrate reads "
                        + "the password of the owner account from its standard input"));
        JavaProcess.Result result = cli(false, ownerPassword + "\n", "migrate", "--owner-account=" + database.user(),
                "--stdin", "--app-account=" + database.appUser());
        assertThat(result.exit()).as(result.streams()).isZero();

        assertThat(owner.schemaExists(schema)).isTrue();
        List<String> tables = owner.rows("SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                + schema + "'").stream().map(row -> row.get("table_name")).toList();
        assertThat(tables).contains("project", "defect", "task", "history", "request_receipt", "session_key",
                "session_grant", "schema_history");
        schemaHistoryAfterMigrate = schemaHistory(schema);
        assertThat(schemaHistoryAfterMigrate).hasSize(3).allSatisfy(row -> assertThat(row.get("outcome"))
                .isEqualTo("SUCCESS"));
        assertThat(privileges("history")).containsExactlyInAnyOrder("SELECT", "INSERT");
        assertThat(privileges("defect")).contains("SELECT", "INSERT", "UPDATE").doesNotContain("DELETE");
        // the step of the DBMS that the grant template leaves to the operator: the account may look into the schema
        owner.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + database.appUser());
    }

    @Test
    @Order(5)
    @DisplayName("SYS-005: the owner sets up the project, the key made by the hook jar and its grants with the commands of the product")
    void ownerSetup() throws IOException {
        projectId = "sysproj";
        JavaProcess.Result project = cli(true, null, "project-add", projectId, "SYSTEST", "SystemTest");
        assertThat(project.exit()).as(project.streams()).isZero();
        // the common codes of a new installation hold no defect category: the owner sets the one the defects use
        JavaProcess.Result code = cli(true, null, "code-set", "DEFECT_CATEGORY", "ARC", "Architecture");
        assertThat(code.exit()).as(code.streams()).isZero();

        hostId = "sys" + UUID.randomUUID().toString().substring(0, 8);
        author = "claude:" + hostId;
        // not created here: the hook makes its key folder owner-only and refuses one that others can use
        keys = work.resolve("keys");
        JavaProcess.Result printed = hook(keys, "", "session-key", "ledger", author);
        assertThat(printed.exit()).as(printed.streams()).isZero();
        List<String> lines = printed.out().lines().toList();
        assertThat(lines).hasSize(4);
        String publicKey = lines.get(2).substring("public-key: ".length());
        String fingerprint = lines.get(3).substring("fingerprint: ".length());

        JavaProcess.Result registered = cli(true, null, "key-register", author, publicKey, fingerprint);
        assertThat(registered.exit()).as(registered.streams()).isZero();
        assertThat(owner.value("SELECT fingerprint FROM " + schema + ".session_key WHERE author_id = '" + author
                + "' AND revoked_at IS NULL")).isEqualTo(fingerprint);

        JavaProcess.Result granted = cli(true, null, "grant", author, projectId, "defect", "task");
        assertThat(granted.exit()).as(granted.streams()).isZero();
        JavaProcess.Result listed = cli(true, null, "grants", author);
        assertThat(listed.exit()).as(listed.streams()).isZero();
        assertThat(listed.out()).contains(author, projectId, "defect", "task");
    }

    // ---- the server ----------------------------------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("SYS-006: the packaged server starts with the registered driver, the schema guard and the application account; mcp-check passes")
    void serverStartsAndMcpCheckPasses() throws Exception {
        require(serverArguments, "SYS-001");
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < serverArguments.size(); i++) {
            String argument = serverArguments.get(i);
            // the jar of the rendered service definition is the built jar; every other argument is the service's own
            arguments.add(i > 0 && serverArguments.get(i - 1).equals("-jar") ? mcpJar.toString() : argument);
        }
        arguments.add("--ledger.jdbc.pool-size=2");
        server = JavaProcess.start(runs, appEnvironment(), work.resolve("server.log"), arguments);

        awaitHealthy();
        JavaProcess.Result check = cli(false, null, "mcp-check", "--url=http://127.0.0.1:" + port + "/mcp");
        assertThat(check.exit()).as(check.streams()).isZero();
        assertThat(check.out()).contains("/mcp");
    }

    @Test
    @Order(7)
    @DisplayName("SYS-007: the writes signed by the real hook jar, whoami and the read tools work against the packaged server")
    void signedWritesAndReads() throws IOException {
        require(server, "SYS-006");
        openClient();

        Map<String, Object> defect = Map.of("entity", "defect", "reason", REASON, "fields", Map.of("project_id", projectId,
                "title", "first title", "category", "ARC", "severity", "P2", "behavior", "behavior of the defect",
                "root_cause", "cause of the defect"));
        JsonNode created = write("ledger_save", defect);
        defectId = created.path("id").asString();
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(created.path("author").asString()).isEqualTo(author);
        defectBeforeEdit = row("SELECT title, status, severity, category, version FROM " + schema + ".defect WHERE id = '"
                + defectId + "'");

        JsonNode edited = write("ledger_save", Map.of("entity", "defect", "id", defectId, "expected_version", 1,
                "reason", REASON, "fields", Map.of("title", "second title")));
        assertThat(edited.path("version").asLong()).isEqualTo(2);

        JsonNode task = write("ledger_save", Map.of("entity", "task", "reason", REASON, "fields", Map.of("project_id",
                projectId, "title", "the task", "purpose", "purpose of the task", "success_condition", "success of the task")));
        taskId = task.path("id").asString();
        assertThat(task.path("version").asLong()).isEqualTo(1);
        taskBeforeTransition = row("SELECT title, state, version FROM " + schema + ".task WHERE id = '" + taskId + "'");

        JsonNode transitioned = write("ledger_transition", Map.of("entity", "task", "id", taskId, "expected_version", 1,
                "to", "ACTIVE", "reason", REASON));
        assertThat(transitioned.path("to").asString()).isEqualTo("ACTIVE");
        assertThat(transitioned.path("version").asLong()).isEqualTo(2);

        // whoami is signed too (an empty request id); it is not a write
        JsonNode who = call("ledger_whoami", hooked("ledger_whoami", Map.of()));
        assertThat(who.path("author").asString()).isEqualTo(author);
        assertThat(who.path("grants").toString()).contains("defect").contains("task").contains(projectId);

        JsonNode history = call("ledger_history", Map.of("project", projectId, "include_before", true));
        List<String> ops = new ArrayList<>();
        history.path("items").forEach(item -> ops.add(item.path("op").asString()));
        assertThat(ops).containsExactly("CREATE", "EDIT", "CREATE", "TRANSITION");
        assertThat(history.path("items").get(1).path("before_data").path("title").asString()).isEqualTo("first title");
        assertThat(call("ledger_projects", Map.of()).path("items").toString()).contains(projectId);
    }

    @Test
    @Order(8)
    @DisplayName("SYS-008: the rows, one receipt per write and the before_data are in the database as the writes made them")
    void rowsReceiptsAndBeforeData() throws IOException {
        require(defectId, "SYS-007");
        assertThat(rows("SELECT title, version FROM " + schema + ".defect")).containsExactly(
                Map.of("title", "second title", "version", "2"));
        assertThat(rows("SELECT state, version FROM " + schema + ".task")).containsExactly(
                Map.of("state", "ACTIVE", "version", "2"));

        List<Map<String, String>> history = rows("SELECT op, entity, request_id, before_data FROM " + schema
                + ".history ORDER BY id");
        assertThat(history).extracting(row -> row.get("op")).containsExactly("CREATE", "EDIT", "CREATE", "TRANSITION");
        assertThat(history).extracting(row -> row.get("request_id")).containsExactlyElementsOf(sentRequestIds);
        assertThat(history.get(0).get("before_data")).isNull();
        assertThat(history.get(2).get("before_data")).isNull();
        assertThat(beforeValues(history.get(1).get("before_data"), "title", "status", "severity", "category", "version"))
                .isEqualTo(defectBeforeEdit);
        assertThat(beforeValues(history.get(3).get("before_data"), "title", "state", "version"))
                .isEqualTo(taskBeforeTransition);

        List<Map<String, String>> receipts = rows("SELECT request_id, operation FROM " + schema
                + ".request_receipt ORDER BY created_at, request_id");
        assertThat(receipts).extracting(row -> row.get("request_id")).containsExactlyInAnyOrderElementsOf(sentRequestIds);
        assertThat(receipts).extracting(row -> row.get("operation")).containsExactlyInAnyOrder("SAVE", "SAVE", "SAVE",
                "TRANSITION");
        historyRowsAfterWrites = history.size();
        receiptsAfterWrites = receipts.size();
    }

    @Test
    @Order(9)
    @DisplayName("SYS-009: migrate as the application account is refused, nothing is created and schema_history does not change")
    void migrateAsApplicationAccountIsRefused() {
        require(schemaHistoryAfterMigrate, "SYS-004");
        JavaProcess.Result result = cli(true, null, "migrate", "--app-account=" + database.appUser(),
                "--ledger.schema.name=" + otherSchema);
        assertThat(result.exit()).as(result.streams()).isNotZero();
        assertThat(owner.schemaExists(otherSchema)).as("the second schema must not exist").isFalse();
        assertThat(schemaHistory(schema)).isEqualTo(schemaHistoryAfterMigrate);
    }

    @Test
    @Order(10)
    @DisplayName("SYS-010: a write without the attestation of the hook is refused by the packaged server and writes nothing")
    void unsignedWriteIsRefused() throws IOException {
        require(client, "SYS-007");
        CallToolResult result = send("ledger_save", Map.of("entity", "defect", "reason", REASON, "fields", Map.of(
                "project_id", projectId, "title", "unsigned", "category", "ARC", "severity", "P2", "behavior", "b",
                "root_cause", "c")));
        assertThat(result.isError()).as("the unsigned write must fail, got %s", result.content()).isTrue();
        assertThat(mapper.valueToTree(result.structuredContent()).path("code").asString())
                .isEqualTo("HOST_ATTESTATION_REQUIRED");
        assertThat(rows("SELECT id FROM " + schema + ".history")).hasSize(historyRowsAfterWrites);
        assertThat(rows("SELECT request_id FROM " + schema + ".request_receipt")).hasSize(receiptsAfterWrites);
    }

    // ---- the end -------------------------------------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("SYS-011: the server process ends and the schemas are dropped")
    void stopAndDrop() {
        close();
        assertThat(server == null || !server.isAlive()).as("the server process has ended").isTrue();
        for (String name : List.of(schema, otherSchema)) {
            owner.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
            assertThat(owner.schemaExists(name)).as("the schema %s is dropped", name).isFalse();
        }
    }

    @Test
    @Order(12)
    @DisplayName("SYS-012: no file of the instance and no output of a process holds a password")
    void noPasswordOnDisk() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> passwords = new ArrayList<>();
        database.ownerPassword().ifPresent(passwords::add);
        database.appPassword().ifPresent(passwords::add);
        try (Stream<Path> files = Files.walk(work)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String password : passwords) {
                    if (content.contains(new String(password.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1))) {
                        offenders.add(work.relativize(file).toString());
                    }
                }
            }
        }
        // the paths only: a password is never part of a message
        assertThat(offenders).as("files that hold a password").isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /**
     * Runs the command line tool as a process. The settings of the instance are the ones the services use: the home of the
     * installation and a pool of two connections. {@code asApplication} puts the password of the application account into
     * the environment of this one process (the owner of {@code migrate} gives its password on the standard input).
     */
    private JavaProcess.Result cli(boolean asApplication, String stdin, String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.add("--ledger.secrets.home=" + slash(etc));
        all.add("--ledger.jdbc.pool-size=2");
        try {
            return JavaProcess.run(runs, asApplication ? appEnvironment() : Map.of(), stdin, PROCESS_SECONDS, cliJar,
                    all.toArray(String[]::new));
        } catch (IOException failure) {
            throw new IllegalStateException("the process of ledger-cli could not be run", failure);
        }
    }

    private JavaProcess.Result hook(Path keyFolder, String stdin, String... arguments) throws IOException {
        return JavaProcess.run(runs, Map.of("LEDGER_HOOK_KEY_DIR", keyFolder.toString()), stdin, 60, hookJar, arguments);
    }

    /** The environment of a process that connects as the application account: its password, as the settings bind it. */
    private Map<String, String> appEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        database.appPassword().ifPresent(password -> environment.put(PASSWORD_VARIABLE, password));
        return environment;
    }

    /** The call as the model makes it, run through the hook jar as Claude Code does: what comes back is what is sent. */
    private Map<String, Object> hooked(String tool, Map<String, Object> input) throws IOException {
        String event = mapper.writeValueAsString(Map.of("tool_name", "mcp__ledger__" + tool, "tool_input", input,
                "session_id", hostId));
        JavaProcess.Result run = hook(keys, event, "claude");
        assertThat(run.exit()).as(run.streams()).isZero();
        JsonNode updated = mapper.readTree(run.out()).path("hookSpecificOutput").path("updatedInput");
        assertThat(updated.isObject()).as("the hook answered %s", run.out()).isTrue();
        Map<String, Object> sent = new LinkedHashMap<>();
        updated.properties().forEach(entry -> sent.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class)));
        assertThat(String.valueOf(sent.get("_hostAttestation"))).startsWith("pgla2.");
        return sent;
    }

    /** A write: signed by the hook, sent, and it must succeed; the request id the hook made is kept. */
    private JsonNode write(String tool, Map<String, Object> input) throws IOException {
        Map<String, Object> sent = hooked(tool, input);
        sentRequestIds.add(String.valueOf(sent.get("request_id")));
        return call(tool, sent);
    }

    private JsonNode call(String tool, Map<String, Object> sent) {
        CallToolResult result = send(tool, sent);
        assertThat(result.isError()).as("%s failed: %s", tool, result.content()).isFalse();
        return mapper.valueToTree(result.structuredContent());
    }

    private CallToolResult send(String tool, Map<String, Object> arguments) {
        return client.callTool(CallToolRequest.builder(tool)
                .arguments(new JacksonMcpJsonMapper(mapper), mapper.writeValueAsString(arguments)).build());
    }

    private void openClient() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .addHttpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + token))
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
    }

    /** Closes the client and ends the server process; safe to call again. */
    private void close() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                // the server is stopped next; a client that cannot close gracefully holds nothing the test needs
            }
            client = null;
        }
        JavaProcess.stop(server);
    }

    /** Polls {@code /healthz} until the server answers UP; fails at once when the process ends (with its log). */
    private void awaitHealthy() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                .timeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(PROCESS_SECONDS).toNanos();
        String last = "no answer yet";
        while (System.nanoTime() < deadline) {
            assertThat(server.isAlive()).as("the server ended with exit %s; its log:\n%s", server.isAlive() ? "-" : server.exitValue(),
                    JavaProcess.tail(JavaProcess.text(work.resolve("server.log"))))
                    .isTrue();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                last = response.statusCode() + " " + response.body();
                if (response.statusCode() == 200 && response.body().contains("UP")) {
                    return;
                }
            } catch (IOException notYet) {
                last = notYet.getClass().getSimpleName();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the server was not healthy in " + PROCESS_SECONDS + " seconds (last: " + last
                + "); its log:\n" + JavaProcess.tail(JavaProcess.text(work.resolve("server.log"))));
    }

    private List<Map<String, String>> rows(String sql) {
        return owner.rows(sql);
    }

    private Map<String, String> row(String sql) {
        List<Map<String, String>> rows = owner.rows(sql);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, String>> schemaHistory(String name) {
        return owner.rows("SELECT version, file_name, sha256, applied_at, outcome FROM " + name
                + ".schema_history ORDER BY version, applied_at");
    }

    /** The privileges the application account holds on a table of the schema (information_schema, ANSI). */
    private List<String> privileges(String table) {
        return owner.rows("SELECT privilege_type FROM information_schema.table_privileges WHERE table_schema = '" + schema
                + "' AND table_name = '" + table + "' AND grantee = '" + database.appUser() + "'").stream()
                .map(row -> row.get("privilege_type")).toList();
    }

    /** The named keys of the JSON text of a before_data, as text. */
    private Map<String, String> beforeValues(String json, String... keys) {
        JsonNode node = mapper.readTree(json);
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, node.path(key).asString());
        }
        return values;
    }

    /**
     * Makes a file or directory usable by its owner alone on a file system with ACLs (one ALLOW entry, nothing inherited);
     * on a file system without ACLs the permissions the process created it with are what the product checks, so nothing is
     * changed.
     */
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

    private static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static Path jar(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the system property " + property + " is not set (the build of ledger-system-test passes it)");
        }
        Path path = Path.of(value);
        assertThat(path).as("the jar of %s", property).isRegularFile();
        return path;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** A step that an earlier case makes is missing: say which one instead of a null pointer. */
    private static void require(Object fact, String step) {
        assertThat(fact).as("the fact of %s: that case did not run or failed", step).isNotNull();
    }
}
