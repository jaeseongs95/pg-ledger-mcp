package org.pgledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.deploy.DeployLayout;
import org.pgledger.cli.deploy.RenderDeployCommand;
import org.pgledger.cli.seed.InstallDefaults;
import org.pgledger.cli.seed.PackagedSeed;
import org.pgledger.domain.rule.ToolContract;
import org.springframework.core.io.ClassPathResource;

/**
 * DOC-001 .. DOC-029 (and the documentation part of ARC-031): the READMEs, the release note format and the release notes
 * are read as text and checked against the code. Every list that must mirror the code (MCP tools, ledger-cli commands and
 * their syntax, modules, client directories, configuration keys) is derived from the code here and never copied. One case
 * is one invocation of a parameterized test.
 */
class DocsTest {

    private static final String LEGACY_MARKER = "1.x";
    private static final String NOTE = "docs/release-notes/2.0.0.md";
    private static final String FORMAT = "docs/RELEASE_NOTES_FORMAT.md";
    private static final List<String> REMOVED_TOOLS = List.of("execute_sql_reader", "search_objects_reader");
    /** Changed by install: {@code deploy/} holds the installer of 2.x (deploy/install.sh), it is not a 1.x directory any more. */
    private static final List<String> LEGACY_DIRECTORIES = List.of("bin", "web", "mcp", "sql", "tests");
    private static final String INSTALL_SCRIPT = "deploy/install.sh";
    /** An option of the loop of install.sh: {@code --name=*)} (with a value) or {@code --stdin)} (a flag); a digit is allowed ({@code --from-1x-env}). */
    private static final Pattern SCRIPT_OPTION = Pattern.compile("--([a-z][a-z0-9-]*)(?:=\\*)?\\)");
    private static final List<String> INSTALL_NOTE_TERMS = List.of("deploy/install.sh", "deploy\\install.cmd", "render-deploy",
            "token-generate", "dml-grants.sql", "ledger.secrets.home");
    /** Limitations the 2.0.0 note listed before the installers existed; they are untrue now (DOC-032). */
    private static final List<String> OLD_LIMITATIONS = List.of("An installer is not part of this release",
            "use one database account", "설치기는 이번 릴리스에 포함되지 않으며", "데이터베이스 계정 하나를 씁니다");
    private static final List<String> REQUIRED_KEYS = List.of("ledger.mcp.token", "ledger.admin.token",
            "ledger.admin.agent-token", "ledger.admin.driver-directory", "ledger.admin.oidc.owners",
            "ledger.admin.lockout.global-delay", "ledger.jdbc.url", "ledger.schema.name");
    private static final List<String> NOTE_TERMS = List.of("pgla2", "pgla1", "ATTESTATION_VERSION_UNSUPPORTED",
            "REQUEST_ID_LEGACY", "execute_sql_reader", "search_objects_reader", "rows[0].result",
            "notifications/tools/list_changed", "key-register", "import-legacy", "migrate", "secret-set", "D-0012",
            "ledger.admin.agent-token", "ledger.admin.driver-directory", "ledger.admin.oidc.owners",
            "ledger.admin.lockout.global-delay");

    private static final Pattern KEY = Pattern.compile("ledger(\\.[a-z0-9-]+)+");
    private static final Pattern CONFIGURATION_PROPERTIES = Pattern
            .compile("@ConfigurationProperties\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([a-z.-]+)\"");
    private static final Pattern MCP_TOOL = Pattern.compile("@McpTool\\(\\s*name\\s*=\\s*\"([a-z_]+)\"");
    private static final Pattern MODULE = Pattern.compile("\"(ledger-[a-z-]+)\"");
    private static final Pattern VENDOR = Pattern.compile(
            "(?i)\\b(PostgreSQL|Postgres|MySQL|MariaDB|Oracle|SQL Server|H2|DB2|SQLite|Derby|HSQLDB)\\b");
    private static final Pattern SUPPORT_WORD = Pattern.compile("(?i)support|compatib|certif|tested|지원|호환|검증");

    /** A thing that belongs to the 1.x implementation (or to its way of installing) and so never to the new product text. */
    private record Term(String label, Pattern pattern) {
    }

    private static final List<Term> LEGACY_TERMS = List.of(
            new Term("DBHub", Pattern.compile("(?i)dbhub")),
            new Term("Node", Pattern.compile("(?i)\\bnode(\\.js)?\\b")),
            new Term("npm", Pattern.compile("\\bnpm\\b")),
            new Term(".mjs", Pattern.compile("\\.mjs\\b")),
            new Term("Cloudflare", Pattern.compile("(?i)cloudflare")),
            new Term("1.x shell installers", Pattern.compile("(?i)one-click|setup-domain")),
            new Term("PostgreSQL server tools", Pattern.compile("(?i)\\bpsql\\b|pg_hba|SECURITY DEFINER|pg_dump")),
            new Term("1.x directories", Pattern.compile("(?<![\\w/.-])(?:bin|web|mcp|sql|tests)/")));

    // ---- sources of the lists: always the code ----

    private static List<String> modules() {
        Matcher matcher = MODULE.matcher(Repository.read("settings.gradle.kts"));
        List<String> modules = new ArrayList<>();
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }
        assertThat(modules).as("modules of settings.gradle.kts").isNotEmpty();
        return modules;
    }

    private static List<String> cliCommandNames() {
        return Stream.of(CliCommand.values()).map(CliCommand::cliName).toList();
    }

    /** The syntax of a command: the text of its usage line before the three spaces that start the description. */
    private static String syntaxOf(String command) {
        Properties bundle = new Properties();
        try (InputStream stream = new ClassPathResource("messages.properties").getInputStream()) {
            bundle.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("messages.properties cannot be read", e);
        }
        String usage = bundle.getProperty(CliCommand.parse(command).orElseThrow().usageKey());
        return usage.substring(0, usage.indexOf("   "));
    }

    /** Where each {@code @ConfigurationProperties} prefix is declared: the text of the file that declares it. */
    private static List<Declaration> declarations() {
        List<Declaration> declarations = new ArrayList<>();
        for (String module : modules()) {
            if (Repository.isDirectory(module + "/src/main/java")) {
                for (Path file : Repository.files(module + "/src/main/java", ".java")) {
                    String text = Repository.read(file);
                    Matcher matcher = CONFIGURATION_PROPERTIES.matcher(text);
                    while (matcher.find()) {
                        declarations.add(new Declaration(matcher.group(1), text));
                    }
                }
            }
        }
        return declarations;
    }

    private record Declaration(String prefix, String text) {

        /** The key is the prefix or below it, and every further segment is a component name in the declaring file. */
        boolean declares(String key) {
            if (!key.equals(prefix) && !key.startsWith(prefix + ".")) {
                return false;
            }
            return Arrays.stream(key.substring(prefix.length()).split("\\.")).filter(segment -> !segment.isEmpty())
                    .allMatch(segment -> Pattern.compile("\\b" + camel(segment) + "\\b").matcher(text).find());
        }

        private static String camel(String kebab) {
            String[] words = kebab.split("-");
            StringBuilder camel = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) {
                camel.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
            }
            return camel.toString();
        }
    }

    private static Stream<Arguments> languageAnd(List<String> items) {
        return Stream.of(Language.values())
                .flatMap(language -> items.stream().map(item -> Arguments.argumentSet(language + " " + item, language, item)));
    }

    private static Markdown note() {
        return Markdown.of(Repository.read(NOTE));
    }

    private static Markdown format() {
        return Markdown.of(Repository.read(FORMAT));
    }

    // ---- README pair ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-001 README.md and README.ko.md exist and each links the other one near the top")
    void readmesExistAndLinkEachOther(Language language) {
        String top = String.join("\n", language.readme().lines().stream().limit(12).toList());

        assertThat(top).contains("](" + language.other().readmeFile() + ")");
    }

    // ---- MCP tools ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-002 the tables of MCP tools list exactly the tools of ToolContract: every write and read tool, nothing else")
    void toolTablesListExactlyTheToolsOfTheServer(Language language) {
        List<String> documented = language.readme().tables().stream()
                .filter(table -> language.toolHeader().equals(table.firstHeader()))
                .flatMap(table -> table.firstCellNames().stream()).toList();

        assertThat(documented).containsExactlyInAnyOrderElementsOf(ToolContract.all());
    }

    @ParameterizedTest
    @MethodSource("removedToolCases")
    @DisplayName("DOC-003 a removed tool (execute_sql_reader, search_objects_reader) is named only in lines that say it is removed, never in a table of tools")
    void removedToolsAreNeverPresentedAsAvailable(Language language, String tool) {
        List<String> lines = language.readme().lines().stream().filter(line -> line.contains(tool)).toList();

        assertThat(lines).as("the migration text names %s", tool).isNotEmpty();
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.strip()).doesNotStartWith("|");
            assertThat(line.toLowerCase(Locale.ROOT)).contains(language.removedMarker());
        });
    }

    static Stream<Arguments> removedToolCases() {
        return languageAnd(REMOVED_TOOLS);
    }

    @Test
    @DisplayName("DOC-004 the premise of DOC-002: the names of the @McpTool registrations of the server sources are exactly ToolContract")
    void theRegistrationsOfTheServerAreTheContract() {
        Set<String> registered = new TreeSet<>();
        for (Path file : Repository.files("ledger-mcp-server/src/main/java", ".java")) {
            Matcher matcher = MCP_TOOL.matcher(Repository.read(file));
            while (matcher.find()) {
                registered.add(matcher.group(1));
            }
        }

        assertThat(registered).containsExactlyInAnyOrderElementsOf(ToolContract.all());
    }

    // ---- ledger-cli commands ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-005 the tables of ledger-cli commands list exactly the commands of CliCommand")
    void commandTablesListExactlyTheCommandsOfTheCli(Language language) {
        List<String> documented = language.readme().tables().stream()
                .filter(table -> language.commandHeader().equals(table.firstHeader()))
                .flatMap(table -> table.firstCellNames().stream()).toList();

        assertThat(documented).containsExactlyInAnyOrderElementsOf(cliCommandNames());
    }

    static Stream<Arguments> commandCases() {
        return languageAnd(cliCommandNames());
    }

    @ParameterizedTest
    @MethodSource("commandCases")
    @DisplayName("DOC-006 the row of each command shows the syntax of its usage line (the usage text of ledger-cli is the source) as the second cell")
    void commandRowsShowTheSyntaxOfTheUsageText(Language language, String command) {
        List<String> row = language.readme().tables().stream()
                .filter(table -> language.commandHeader().equals(table.firstHeader()))
                .map(table -> table.rowOf(command)).filter(Objects::nonNull).findFirst().orElse(null);

        assertThat(row).as("row of %s", command).isNotNull();
        assertThat(row.get(1)).isEqualTo("`" + syntaxOf(command) + "`");
    }

    // ---- what the text must not say ----

    static Stream<Arguments> legacyTermCases() {
        return Stream.of(Language.values()).flatMap(language -> LEGACY_TERMS.stream()
                .map(term -> Arguments.argumentSet(language + " " + term.label(), language, term)));
    }

    @ParameterizedTest
    @MethodSource("legacyTermCases")
    @DisplayName("DOC-007 DBHub, Node, npm, .mjs, Cloudflare, the 1.x shell installers, the PostgreSQL server tools and the 1.x directories appear only in a line that says 1.x (the Layout note); deploy/install.sh is the installer of 2.x and not a 1.x term")
    void legacyTermsAppearOnlyInLinesThatSayTheyAreOneX(Language language, Term term) {
        List<String> offending = language.readme().lines().stream().filter(line -> term.pattern().matcher(line).find())
                .filter(line -> !line.contains(LEGACY_MARKER)).toList();

        assertThat(offending).isEmpty();
    }

    static Stream<Arguments> legacyDirectoryCases() {
        return languageAnd(LEGACY_DIRECTORIES.stream().filter(Repository::isDirectory).toList());
    }

    @ParameterizedTest
    @MethodSource("legacyDirectoryCases")
    @DisplayName("DOC-008 every 1.x directory that is still in the tree is named in a line that says it is the 1.x implementation (so DOC-007 cannot be met by leaving the Layout silent)")
    void everyLegacyDirectoryInTheTreeIsNamedAsOneX(Language language, String directory) {
        List<String> lines = language.readme().lines().stream()
                .filter(line -> line.contains(LEGACY_MARKER) && line.contains(directory + "/")).toList();

        assertThat(lines).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-009 (ARC-031) no list of databases: a line names at most one DBMS")
    void noLineNamesTwoDatabases(Language language) {
        List<String> offending = language.readme().lines().stream().filter(line -> {
            Set<String> names = new TreeSet<>();
            Matcher matcher = VENDOR.matcher(line);
            while (matcher.find()) {
                names.add(matcher.group().toLowerCase(Locale.ROOT));
            }
            return names.size() > 1;
        }).toList();

        assertThat(offending).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-010 (ARC-031) no DBMS is named as supported, compatible, certified or tested")
    void noDatabaseIsNamedAsSupported(Language language) {
        List<String> offending = language.readme().lines().stream()
                .filter(line -> VENDOR.matcher(line).find() && SUPPORT_WORD.matcher(line).find()).toList();

        assertThat(offending).isEmpty();
    }

    static Stream<Arguments> requiredPhraseCases() {
        return Stream.of(Language.values()).flatMap(language -> language.requiredPhrases().stream()
                .map(phrase -> Arguments.argumentSet(language + " " + phrase, language, phrase)));
    }

    @ParameterizedTest
    @MethodSource("requiredPhraseCases")
    @DisplayName("DOC-011 (ARC-031) the README states the DBMS wording (JDBC driver attached by the user and not bundled, ANSI SQL only, no DBMS-specific feature, UTC) and the install procedure (sudo deploy/install.sh)")
    void readmeStatesTheDatabaseWordingAndTheInstaller(Language language, String phrase) {
        assertThat(language.readme().text()).contains(phrase);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-030 the README no longer says that the installer is planned")
    void readmeDoesNotSayTheInstallerIsPlanned(Language language) {
        assertThat(language.readme().text()).doesNotContain(language.plannedInstallerSentence());
    }

    /** The options of the installer: those of the script (derived from its option loop) and those of render-deploy. */
    private static List<String> installerOptions() {
        List<String> options = new ArrayList<>();
        Matcher matcher = SCRIPT_OPTION.matcher(Repository.read(INSTALL_SCRIPT));
        while (matcher.find()) {
            options.add(matcher.group(1));
        }
        options.addAll(RenderDeployCommand.OPTIONS);
        return options.stream().distinct().sorted().toList();
    }

    static Stream<Arguments> installerNameCases() {
        String defaultName = new InstallDefaults(new PackagedSeed()).instanceName();
        List<String> names = new ArrayList<>(List.of(defaultName, DeployLayout.linux(defaultName).adminName()));
        installerOptions().forEach(option -> names.add("--" + option));
        return languageAnd(names);
    }

    @ParameterizedTest
    @MethodSource("installerNameCases")
    @DisplayName("DOC-031 the README names the two services and documents every option of the installer (the options of deploy/install.sh and of render-deploy, derived from the code) as a code span")
    void readmeDocumentsTheInstaller(Language language, String name) {
        boolean documented = language.readme().codeSpans().stream()
                .anyMatch(span -> span.equals(name) || span.startsWith(name + "="));

        assertThat(documented).as("a code span of the README for %s", name).isTrue();
    }

    static Stream<Arguments> installNoteCases() {
        return languageAnd(INSTALL_NOTE_TERMS);
    }

    @ParameterizedTest
    @MethodSource("installNoteCases")
    @DisplayName("DOC-032 each part of the 2.0.0 note names the two installers, their Java commands, the grant template and the settings home")
    void noteNamesTheInstaller(Language language, String term) {
        assertThat(note().part(language.partHeading()).text()).contains(term);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-032 the limitations that no installer exists and that the servers share one database account by default are gone from the 2.0.0 note")
    void noteDoesNotKeepTheOldLimitations(Language language) {
        String part = note().part(language.partHeading()).text();

        assertThat(OLD_LIMITATIONS).allSatisfy(sentence -> assertThat(part).doesNotContain(sentence));
    }

    // ---- what the text must say, derived from the code ----

    static Stream<Arguments> moduleCases() {
        return languageAnd(modules());
    }

    @ParameterizedTest
    @MethodSource("moduleCases")
    @DisplayName("DOC-012 every Gradle module of settings.gradle.kts is named in the README as a code span")
    void everyModuleIsNamed(Language language, String module) {
        assertThat(language.readme().codeSpans()).contains(module);
    }

    static Stream<Arguments> clientCases() {
        return languageAnd(Repository.subdirectories("clients").stream().map(directory -> "clients/" + directory).toList());
    }

    @ParameterizedTest
    @MethodSource("clientCases")
    @DisplayName("DOC-013 every client directory below clients/ is named in the README")
    void everyClientDirectoryIsNamed(Language language, String directory) {
        assertThat(language.readme().text()).contains(directory);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-014 the README names the one hook jar of the three clients")
    void theHookJarIsNamed(Language language) {
        assertThat(language.readme().text()).contains("ledger-client-hook.jar");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-015 every configuration key written as a code span exists: its prefix is a @ConfigurationProperties prefix and each further segment is a component of the declaring file")
    void documentedConfigurationKeysExist(Language language) {
        List<Declaration> declarations = declarations();
        List<String> unknown = language.readme().codeSpans().stream().filter(span -> KEY.matcher(span).matches())
                .filter(key -> declarations.stream().noneMatch(declaration -> declaration.declares(key))).toList();

        assertThat(unknown).isEmpty();
    }

    static Stream<Arguments> requiredKeyCases() {
        return languageAnd(REQUIRED_KEYS);
    }

    @ParameterizedTest
    @MethodSource("requiredKeyCases")
    @DisplayName("DOC-016 the deploy settings are documented: tokens, agent-token, driver directory, OIDC owners, global-delay, JDBC url, schema name")
    void deploySettingsAreDocumented(Language language, String key) {
        assertThat(language.readme().codeSpans()).contains(key);
    }

    // ---- licence (DOC-033) ----

    @Test
    @DisplayName("DOC-033 LICENSE is the text of the GNU Affero General Public License version 3 and not the MIT License")
    void licenseIsTheAgplText() {
        String text = Repository.read("LICENSE");

        assertThat(text).startsWith("                    GNU AFFERO GENERAL PUBLIC LICENSE\n                       Version 3, 19 November 2007\n");
        assertThat(text).contains("END OF TERMS AND CONDITIONS").contains("13. Remote Network Interaction; Use with the GNU General Public License.")
                .doesNotContain("MIT License");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-033 the License section of the README states AGPL-3.0-or-later, the MIT License only for the published v1.0.0 and v1.1.0 and the copyright line")
    void readmeStatesTheLicense(Language language) {
        String heading = language == Language.EN ? "License" : "라이선스";
        String section = language.readme().part(heading).text();

        assertThat(section).contains("`AGPL-3.0-or-later`").contains("Copyright (C) 2026 Jaeseong Song").contains("](LICENSE)")
                .contains("MIT").contains("v1.0.0").contains("v1.1.0").doesNotContain("up to 1.x").doesNotContain("1.x까지");
        assertThat(section.lines().filter(line -> !line.isBlank()).toList()).hasSizeGreaterThan(3);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-033 each part of the 2.0.0 note names AGPL-3.0-or-later and the MIT License")
    void noteNamesTheLicense(Language language) {
        String part = note().part(language.partHeading()).text();

        assertThat(part).contains("`AGPL-3.0-or-later`").contains("MIT");
    }

    // ---- the 1.x environment file and the build instruction (DOC-034) ----

    /** The file the 1.x installer wrote and its units read (EnvironmentFile=@ETC@/env, ETC=/etc/$NAME; tags v1.0.0 and v1.1.0). */
    private static final String ONE_X_ENVIRONMENT_FILE = "/etc/pg-ledger-mcp/env";

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-034 the migration command of the README and of each part of the 2.0.0 note names the 1.x environment file /etc/pg-ledger-mcp/env, and no text of the docs names pg-ledger-mcp.env")
    void migrationNamesTheOneXEnvironmentFile(Language language) {
        String readme = language.readme().part(language == Language.EN ? "Migrating from 1.x" : "1.x에서 이전").text();
        String part = note().part(language.partHeading()).text();

        assertThat(readme).contains("--from-1x-env=" + ONE_X_ENVIRONMENT_FILE);
        assertThat(part).contains("--from-1x-env=" + ONE_X_ENVIRONMENT_FILE);
        assertThat(language.readme().text()).doesNotContain("pg-ledger-mcp.env");
        assertThat(part).doesNotContain("pg-ledger-mcp.env");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-034 the jars for an installation are built with ./gradlew assemble (no test database needed); ./gradlew build stays in Build and test")
    void jarsAreBuiltWithAssemble(Language language) {
        String installation = language.readme().part(language == Language.EN ? "Installation" : "설치").text();
        String build = language.readme().part(language == Language.EN ? "Build and test" : "빌드와 시험").text();

        assertThat(installation).contains("`./gradlew assemble`").doesNotContain("`./gradlew build`로").doesNotContain("with `./gradlew build`");
        assertThat(build).contains("./gradlew build");
        assertThat(note().part(language.partHeading()).text()).doesNotContain("pg-ledger-mcp.env");
    }

    // ---- both languages say the same ----

    @Test
    @DisplayName("DOC-017 README.md and README.ko.md have the same number of sections")
    void readmesHaveTheSameSections() {
        assertThat(Language.KO.readme().headings(2)).hasSameSizeAs(Language.EN.readme().headings(2));
    }

    @Test
    @DisplayName("DOC-018 README.md and README.ko.md name the same code (the same set of code spans: tools, commands, keys, values)")
    void readmesNameTheSameCode() {
        assertThat(Language.KO.readme().codeSpans()).isEqualTo(Language.EN.readme().codeSpans());
    }

    @Test
    @DisplayName("DOC-019 README.md and README.ko.md hold the same code blocks (commands and examples are language neutral)")
    void readmesHoldTheSameCodeBlocks() {
        assertThat(Language.KO.readme().fences()).isEqualTo(Language.EN.readme().fences());
    }

    // ---- links and build instructions ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-020 every relative link of the README points to a file or directory of the repository")
    void relativeLinksResolve(Language language) {
        List<String> missing = language.readme().links().stream()
                .filter(link -> !link.startsWith("http") && !link.startsWith("#") && !link.startsWith("mailto:"))
                .map(link -> link.contains("#") ? link.substring(0, link.indexOf('#')) : link)
                .filter(link -> !Repository.exists(link)).toList();

        assertThat(missing).isEmpty();
    }

    static Stream<Arguments> documentCases() {
        List<String> documents = new ArrayList<>(Repository.filesDirectlyIn("docs/release-notes").stream()
                .map(name -> "docs/release-notes/" + name).toList());
        documents.add(FORMAT);
        return languageAnd(documents);
    }

    @ParameterizedTest
    @MethodSource("documentCases")
    @DisplayName("DOC-021 the README links every release note and the release note format")
    void readmeLinksTheReleaseNotes(Language language, String document) {
        assertThat(language.readme().links()).contains(document);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-022 the README gives the build command (./gradlew build) and the JDK of the Gradle toolchain")
    void readmeGivesTheBuildInstructions(Language language) {
        Matcher toolchain = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)").matcher(Repository.read("build.gradle.kts"));
        assertThat(toolchain.find()).isTrue();
        String text = language.readme().text();

        assertThat(text).contains("./gradlew build").contains("JDK " + toolchain.group(1));
    }

    // ---- release note format and release notes ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-023 RELEASE_NOTES_FORMAT.md holds an English and a Korean part in one file, each with a table of sections, each in its own language")
    void formatHoldsBothLanguages(Language language) {
        Markdown part = format().part(language.partHeading());

        assertThat(part.tables()).as("tables of %s", language).isNotEmpty();
        assertThat(part.tables().getFirst().firstHeader()).isEqualTo(language.sectionHeader());
        assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-024 the format names the sections of a release note: Summary, Breaking changes, Migration, New, Changed, Removed, Security, Requirements, Known limitations (and the same nine in Korean)")
    void formatNamesTheSections(Language language) {
        List<String> sections = format().part(language.partHeading()).tables().getFirst().firstCellNames();

        assertThat(sections).containsExactlyElementsOf(language.sections());
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-025 the 2.0.0 note has an English and a Korean part in one file, each with the sections of the format in order and none of them empty")
    void noteFollowsTheFormat(Language language) {
        Markdown part = note().part(language.partHeading());
        List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
        List<Markdown.Subsection> sections = part.subsections();

        assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
        assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
        assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
    }

    @Test
    @DisplayName("DOC-026 the 2.0.0 note starts with the title of the version it is named after")
    void noteStartsWithItsTitle() {
        assertThat(note().lines().stream().filter(line -> !line.isBlank()).findFirst().orElse("")).isEqualTo("# pg-ledger-mcp 2.0.0");
    }

    static Stream<Arguments> noteTermCases() {
        List<String> terms = new ArrayList<>(NOTE_TERMS);
        terms.addAll(ToolContract.READ);
        return languageAnd(terms);
    }

    @ParameterizedTest
    @MethodSource("noteTermCases")
    @DisplayName("DOC-027 each part of the 2.0.0 note names the contract changes, the migration commands, the new settings and every structured read tool")
    void noteNamesTheContractChanges(Language language, String term) {
        assertThat(note().part(language.partHeading()).text()).contains(term);
    }

    @Test
    @DisplayName("DOC-028 the English and the Korean part of the 2.0.0 note name the same code (the same set of code spans)")
    void notePartsNameTheSameCode() {
        assertThat(note().part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(note().part(Language.EN.partHeading()).codeSpans());
    }

    @Test
    @DisplayName("DOC-029 the English and the Korean part of the 2.0.0 note hold the same code blocks")
    void notePartsHoldTheSameCodeBlocks() {
        assertThat(note().part(Language.KO.partHeading()).fences())
                .isEqualTo(note().part(Language.EN.partHeading()).fences());
    }
}
