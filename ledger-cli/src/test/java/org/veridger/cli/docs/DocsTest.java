// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.docs;

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
import org.veridger.cli.CliCommand;
import org.veridger.cli.deploy.DeployLayout;
import org.veridger.cli.deploy.RenderDeployCommand;
import org.veridger.cli.seed.InstallDefaults;
import org.veridger.cli.seed.PackagedSeed;
import org.veridger.domain.rule.ToolContract;
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
    private static final String INSTALL_SCRIPT = "deploy/install.sh";
    /** An option of the loop of install.sh: {@code --name=*)} (with a value) or {@code --stdin)} (a flag); a digit is allowed. */
    private static final Pattern SCRIPT_OPTION = Pattern.compile("--([a-z][a-z0-9-]*)(?:=\\*)?\\)");
    private static final List<String> INSTALL_NOTE_TERMS = List.of("deploy/install.sh", "deploy\\install.cmd", "render-deploy",
            "token-generate", "dml-grants.sql", "ledger.secrets.home");
    /** Limitations the 2.0.0 note listed before the installers existed; they are untrue now (DOC-032). */
    private static final List<String> OLD_LIMITATIONS = List.of("An installer is not part of this release",
            "use one database account", "설치기는 이번 릴리스에 포함되지 않으며", "데이터베이스 계정 하나를 씁니다");
    private static final List<String> REQUIRED_KEYS = List.of("ledger.mcp.token", "ledger.admin.token",
            "ledger.admin.agent-token", "ledger.admin.driver-directory", "ledger.admin.sign-in",
            "ledger.admin.oidc.owner-email", "ledger.admin.oidc.issuer",
            "ledger.admin.passkey.rp-id", "ledger.admin.passkey.origins",
            "ledger.admin.lockout.global-delay", "ledger.jdbc.url", "ledger.schema.name");
    private static final List<String> NOTE_TERMS = List.of("pgla2",
            "execute_sql_reader", "search_objects_reader", "rows[0].result",
            "notifications/tools/list_changed", "key-register", "import-1x", "migrate", "secret-set", "D-0012",
            "ledger.admin.agent-token", "ledger.admin.driver-directory", "ledger.admin.sign-in",
            "ledger.admin.oidc.owner-email", "passkey-code",
            "ledger.admin.lockout.global-delay");

    private static final Pattern KEY = Pattern.compile("ledger(\\.[a-z0-9-]+)+");
    private static final Pattern CONFIGURATION_PROPERTIES = Pattern
            .compile("@ConfigurationProperties\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([a-z.-]+)\"");
    private static final Pattern MCP_TOOL = Pattern.compile("@McpTool\\(\\s*name\\s*=\\s*\"([a-z_-]+)\"");
    private static final Pattern MODULE = Pattern.compile("\"(ledger-[a-z0-9-]+)\"");
    private static final Pattern VENDOR = Pattern.compile(
            "(?i)\\b(PostgreSQL|Postgres|MySQL|MariaDB|Oracle|SQL Server|H2|DB2|SQLite|Derby|HSQLDB)\\b");
    private static final Pattern SUPPORT_WORD = Pattern.compile("(?i)support|compatib|certif|지원|호환");

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
            new Term("PostgreSQL server administration", Pattern.compile("(?i)\\bpsql\\b|pg_hba|SECURITY DEFINER")),
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
        assertThat(language.readme().links()).anyMatch(link -> link.startsWith(language.guideFile() + "#"));
    }

    // ---- MCP tools ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-002 the tables of MCP tools list exactly the tools of ToolContract: every write and read tool, nothing else")
    void toolTablesListExactlyTheToolsOfTheServer(Language language) {
        List<String> documented = language.guide().tables().stream()
                .filter(table -> language.toolHeader().equals(table.firstHeader()))
                .flatMap(table -> table.firstCellNames().stream()).toList();

        assertThat(documented).containsExactlyInAnyOrderElementsOf(ToolContract.all());
    }

    @ParameterizedTest
    @MethodSource("removedToolCases")
    @DisplayName("DOC-003 a removed tool (execute_sql_reader, search_objects_reader) is named only in lines that say it is removed, never in a table of tools")
    void removedToolsAreNeverPresentedAsAvailable(Language language, String tool) {
        List<String> lines = language.guide().lines().stream().filter(line -> line.contains(tool)).toList();

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
        List<String> documented = language.guide().tables().stream()
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
        List<String> row = language.guide().tables().stream()
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
    @DisplayName("DOC-007 legacy tooling and PostgreSQL server administration appear only in the 1.x layout note")
    void legacyTermsAppearOnlyInLinesThatSayTheyAreOneX(Language language, Term term) {
        List<String> offending = language.guide().lines().stream().filter(line -> term.pattern().matcher(line).find())
                .filter(line -> !line.contains(LEGACY_MARKER)).toList();

        assertThat(offending).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-009 (ARC-031) no list of databases: a line names at most one DBMS")
    void noLineNamesTwoDatabases(Language language) {
        List<String> offending = language.guide().lines().stream().filter(line -> {
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
    @DisplayName("DOC-010 (ARC-031) no DBMS is named as supported, compatible or certified (naming the one tested DBMS is allowed)")
    void noDatabaseIsNamedAsSupported(Language language) {
        List<String> offending = language.guide().lines().stream()
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
        assertThat(language.guide().text()).contains(phrase);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-030 the README no longer says that the installer is planned")
    void readmeDoesNotSayTheInstallerIsPlanned(Language language) {
        assertThat(language.guide().text()).doesNotContain(language.plannedInstallerSentence());
    }

    /** The options of the installer: those of the script (derived from its option loop) and those of render-deploy. */
    private static List<String> installerOptions() {
        List<String> options = new ArrayList<>();
        Matcher matcher = SCRIPT_OPTION.matcher(Repository.read(INSTALL_SCRIPT));
        while (matcher.find()) {
            options.add(matcher.group(1));
        }
        options.addAll(RenderDeployCommand.OPTION_NAMES);
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
        boolean documented = language.guide().codeSpans().stream()
                .anyMatch(span -> span.equals(name) || span.startsWith(name + "="));

        assertThat(documented).as("a code span of the README for %s", name).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("PCS-023 (replaces REH4-DOC-01) the README option table shows --mcp-host and --admin-host as one host name each, no comma list")
    void readmeShowsOneHostNameForEachService(Language language) {
        assertThat(language.guide().codeSpans()).anyMatch(span -> "--mcp-host=<host>".equals(span))
                .anyMatch(span -> "--admin-host=<host>".equals(span))
                .noneMatch(span -> (span.startsWith("--mcp-host=") || span.startsWith("--admin-host=")) && span.contains("[,"))
                .noneMatch(span -> span.startsWith("render-deploy ") && span.contains("[,<host>...]"))
                .noneMatch(span -> span.startsWith("prepare-host ") && span.contains("[,<host>...]"));
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
        assertThat(language.guide().codeSpans()).contains(module);
    }

    static Stream<Arguments> clientCases() {
        return languageAnd(Repository.subdirectories("clients").stream().map(directory -> "clients/" + directory).toList());
    }

    @ParameterizedTest
    @MethodSource("clientCases")
    @DisplayName("DOC-013 every client directory below clients/ is named in the README")
    void everyClientDirectoryIsNamed(Language language, String directory) {
        assertThat(language.guide().text()).contains(directory);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-014 the README names the one hook jar of the three clients")
    void theHookJarIsNamed(Language language) {
        assertThat(language.guide().text()).contains("ledger-client-hook.jar");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-015 every configuration key written as a code span exists: its prefix is a @ConfigurationProperties prefix and each further segment is a component of the declaring file")
    void documentedConfigurationKeysExist(Language language) {
        List<Declaration> declarations = declarations();
        List<String> unknown = language.guide().codeSpans().stream().filter(span -> KEY.matcher(span).matches())
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
        assertThat(language.guide().codeSpans()).contains(key);
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
    @DisplayName("DOC-033 the README links the license and the guide documents AGPL-3.0-or-later, earlier MIT releases, and copyright")
    void readmeStatesTheLicense(Language language) {
        String heading = language == Language.EN ? "License" : "라이선스";
        String section = language.guide().part(heading).text();

        assertThat(language.readme().part(heading).links()).contains("LICENSE");
        assertThat(section).contains("`AGPL-3.0-or-later`").contains("Copyright (C) 2026 Jaeseong Song").contains("](../LICENSE)")
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

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("W7-DOC-01 the License section of the README and each part of the 2.0.0 note say that the admin web links to the source code through ledger.source.url, which a modified version points at its own source")
    void licenseNamesTheSourceLink(Language language) {
        String heading = language == Language.EN ? "License" : "라이선스";

        assertThat(language.guide().part(heading).text()).contains("`ledger.source.url`").contains("`http`");
        assertThat(note().part(language.partHeading()).text()).contains("`ledger.source.url`");
    }

    // ---- the upgrade from an earlier major version and the build instruction (DOC-034) ----

    /** The commands and the jar of the separate tool that moves an installation of an earlier major version. */
    private static final List<String> UPGRADE_TOOL_TERMS = List.of("stop-1x", "import-secrets-1x", "import-1x", "retire-1x",
            "ledger-migrate-1x.jar");

    static Stream<Arguments> upgradeToolCases() {
        return languageAnd(UPGRADE_TOOL_TERMS);
    }

    @ParameterizedTest
    @MethodSource("upgradeToolCases")
    @DisplayName("UPG-DOC-01 the README and each part of the 2.0.0 note name the four commands of the separate upgrade tool (stop-1x, import-secrets-1x, import-1x, retire-1x) and its jar ledger-migrate-1x.jar")
    void theUpgradeToolIsNamed(Language language, String term) {
        assertThat(language.guide().text()).contains(term);
        assertThat(note().part(language.partHeading()).text()).contains(term);
    }

    /** The guide of the upgrade tool: the section of the README of the language. */
    private static String upgradeGuide(Language language) {
        return language.guide().part(language == Language.EN ? "Upgrading from 1.x" : "1.x에서 업그레이드").text();
    }

    /** The places in the text where the steps of an upgrade are written, in the order the tool is run (M1X-031, M1X-032). */
    private static final List<String> UPGRADE_ORDER = List.of("ledger-migrate-1x.jar stop-1x", "deploy/install.sh",
            "import-secrets-1x --replace --stdin", "ledger-migrate-1x.jar import-1x --owner-account", "mcp-check",
            "ledger-migrate-1x.jar retire-1x");

    private static void assertInOrder(String text, List<String> markers, String what) {
        int from = 0;
        for (String marker : markers) {
            int found = text.indexOf(marker, from);
            assertThat(found).as("%s: '%s' after position %d", what, marker, from).isGreaterThanOrEqualTo(0);
            from = found;
        }
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("M1X-031 the upgrade guide of each README is in the order stop-1x, the installer, import-secrets-1x, import-1x, the check, retire-1x; it says that the rollback copies the kept unit (the backup stays), that the units and the 1.x database are not deleted before retire-1x, and that Windows has nothing of 1.x to stop")
    void theUpgradeGuideIsInTheOrderOfTheTool(Language language) {
        boolean english = language == Language.EN;
        String guide = upgradeGuide(language);

        assertInOrder(guide, UPGRADE_ORDER, "README " + language);
        assertThat(guide).contains("<unit>.1x-backup").contains("systemctl daemon-reload").contains("enable --now")
                .contains(english ? "so that the backup stays" : "백업이 남도록 복사합니다")
                .contains("NO_1X_SERVICE_WINDOWS").doesNotContain("sc.exe");
        assertNoWindowsRollback(guide, "README " + language);
        // the same commands in the same order in both languages: the fenced commands of the guide are equal
        assertThat(Markdown.of(guide).fences()).isEqualTo(Markdown.of(upgradeGuide(Language.EN)).fences());
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("M1X-032 each part of the 2.0.0 note says the upgrade in the same order, that the rollback copies the kept unit, and that 1.x request receipts are not carried over")
    void theNoteSaysTheUpgradeInTheOrderOfTheTool(Language language) {
        boolean english = language == Language.EN;
        String part = note().part(language.partHeading()).text();

        assertInOrder(part, List.of("`stop-1x`", "`import-secrets-1x`", "`import-1x`", "`retire-1x`"), "2.0.0 note " + language);
        assertThat(part).contains(english ? "so that the backup stays" : "백업이 남도록 복사합니다")
                .contains("NO_1X_SERVICE_WINDOWS");
        // the 2.0.0 installer text of the part names sc.exe for 2.0 (the service managers), so the steps of the upgrade are
        // checked where they are: the section on the migration, and the rollback step wherever it is written
        String migration = note().part(language.partHeading()).subsections().stream()
                .filter(section -> language.sections().get(2).equals(section.heading())).findFirst().orElseThrow().body()
                .stream().reduce("", (left, right) -> left + "\n" + right);
        assertThat(migration).as("migration section of the 2.0.0 note " + language).contains("<unit>.1x-backup")
                .doesNotContain("sc.exe");
        assertNoWindowsRollback(part, "2.0.0 note " + language);
    }

    /**
     * 1.x ran on Linux with systemd only: no line that says how to start the kept 1.x services again (the line holds
     * {@code enable --now}) names Windows or {@code sc.exe}. The earlier check looked for {@code sc.exe qc} in the whole part,
     * which names {@code sc.exe} for the 2.0 installer, so it let "on Windows sc.exe config ..." of the rollback step pass.
     */
    private static void assertNoWindowsRollback(String text, String what) {
        List<String> rollback = text.lines().filter(line -> line.contains("enable --now")).toList();

        assertThat(rollback).as("lines of the rollback in " + what).isNotEmpty();
        assertThat(rollback).as("rollback of 1.x in " + what).noneMatch(line -> line.contains("sc.exe")
                || line.toLowerCase(Locale.ROOT).contains("windows"));
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("M1X-033 the module table of each README names every module that ledger-migrate-1x uses (the project dependencies of its build file)")
    void theModuleRowOfTheToolNamesWhatItUses(Language language) {
        Matcher dependency = Pattern.compile("implementation\\(project\\(\":(ledger-[a-z0-9-]+)\"\\)").matcher(
                Repository.read("ledger-migrate-1x/build.gradle.kts"));
        List<String> used = new ArrayList<>();
        while (dependency.find()) {
            used.add(dependency.group(1));
        }
        String row = language.guide().lines().stream().filter(line -> line.startsWith("| `ledger-migrate-1x` |")).findFirst()
                .orElseThrow();

        assertThat(used).contains("ledger-application", "ledger-cli");
        for (String module : used) {
            assertThat(row).as("row of ledger-migrate-1x in the README, module %s", module).contains("`" + module + "`");
        }
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("REH2-DOC-01 (changed by M1X) the guide of the README and each part of the 2.0.0 note say that the import runs once on the schema that migrate and setup made (the state after the installer), and that the rollback copies the kept unit back (the backup stays); none says the schema is freshly migrated")
    void theGuideSaysWhatTheImportAndTheRollbackLeave(Language language) {
        boolean english = language == Language.EN;

        for (String text : List.of(upgradeGuide(language), note().part(language.partHeading()).text())) {
            assertThat(text).contains(english ? "`migrate` and `setup`" : "`migrate`와 `setup`")
                    .contains(english ? "so that the backup stays" : "백업이 남도록 복사합니다")
                    .doesNotContain(english ? "freshly migrated" : "방금 `migrate`한");
        }
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("REH3-DOC-01 (changed by M1X) the guide of the README and each part of the 2.0.0 note say that the ids of 1.x are kept (1 to 120 characters, any case), that ids that differ only in case are refused, that an empty text of 1.x reads as an empty string, and which columns are longer (code 200, ci_ref 2000)")
    void theGuideSaysWhatHappensToIdsAndTexts(Language language) {
        boolean english = language == Language.EN;

        for (String text : List.of(upgradeGuide(language), note().part(language.partHeading()).text())) {
            if (english) {
                assertThat(text).contains("ids of 1.x are kept as they are").contains("1 to 120 characters")
                        .contains("differ only in case").contains("empty text").contains("`code` of a condition (200")
                        .contains("`ci_ref` (2000");
            } else {
                assertThat(text).contains("1.x의 ID는 그대로 유지").contains("1자 이상 120자 이하")
                        .contains("대소문자만 다른").contains("빈 문자열").contains("조건의 `code`(200자")
                        .contains("`ci_ref`(2000자");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("W7-SEC-01 (changed by M1X and PSEC, LGN-015) the guide of each README says that the admin token of 1.x is not imported, whatever the 1.x installation is (import-secrets-1x keeps the one of the installer)")
    void theAdminTokenOfOneXIsNotImported(Language language) {
        assertThat(upgradeGuide(language)).contains("`PGLEDGER_ADMIN_TOKEN`").contains("`ledger.admin.token`")
                .contains(language == Language.EN ? "is not imported" : "가져오지 않습니다");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("FUP-DOC-03 (changed by M1X) the guide says that the read-only account of the 1.x database may be dropped when the checks are done")
    void theReadOnlyAccountOfOneXMayBeDropped(Language language) {
        String phrase = language == Language.EN ? "read-only account of the 1.x database that was made for the import may be dropped"
                : "읽기 전용 계정은 삭제해도 됩니다";

        assertThat(upgradeGuide(language)).contains(phrase);
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-034 no text of the docs names pg-ledger-mcp.env")
    void noTextNamesTheOldEnvironmentFileName(Language language) {
        assertThat(language.guide().text()).doesNotContain("pg-ledger-mcp.env");
        assertThat(note().part(language.partHeading()).text()).doesNotContain("pg-ledger-mcp.env");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("W7-INS-03 both READMEs list --java=<path> among the options of install.sh and say that the Java runtime is at a path every account can execute, not under /home; so does each part of the 2.0.0 note")
    void javaRuntimeIsAtAPathEveryAccountCanExecute(Language language) {
        String installation = language.guide().part(language == Language.EN ? "Installation" : "설치").text();

        assertThat(installation).contains("| `--java=<path>` |").contains("`/home`");
        assertThat(note().part(language.partHeading()).text()).contains("`--java=<path>`").contains("`/home`");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-034 the jars for an installation are built with ./gradlew assemble (no test database needed); ./gradlew build stays in Build and test")
    void jarsAreBuiltWithAssemble(Language language) {
        String installation = language.guide().part(language == Language.EN ? "Installation" : "설치").text();
        String build = language.guide().part(language == Language.EN ? "Build and test" : "빌드와 시험").text();

        assertThat(installation).contains("`./gradlew assemble`").doesNotContain("`./gradlew build`로").doesNotContain("with `./gradlew build`");
        assertThat(build).contains("./gradlew build");
        assertThat(note().part(language.partHeading()).text()).doesNotContain("pg-ledger-mcp.env");
    }

    // ---- both languages say the same ----

    @Test
    @DisplayName("DOC-017 README.md and README.ko.md have the same number of sections")
    void readmesHaveTheSameSections() {
        assertThat(Language.KO.readme().headings(2)).hasSameSizeAs(Language.EN.readme().headings(2));
        assertThat(Language.KO.guide().headings(2)).hasSameSizeAs(Language.EN.guide().headings(2));
    }

    @Test
    @DisplayName("DOC-018 README.md and README.ko.md name the same code (the same set of code spans: tools, commands, keys, values)")
    void readmesNameTheSameCode() {
        assertThat(Language.KO.readme().codeSpans()).isEqualTo(Language.EN.readme().codeSpans());
        assertThat(Language.KO.guide().codeSpans()).isEqualTo(Language.EN.guide().codeSpans());
    }

    @Test
    @DisplayName("DOC-019 README.md and README.ko.md hold the same code blocks (commands and examples are language neutral)")
    void readmesHoldTheSameCodeBlocks() {
        assertThat(Language.KO.readme().fences()).isEqualTo(Language.EN.readme().fences());
        assertThat(Language.KO.guide().fences()).isEqualTo(Language.EN.guide().fences());
    }

    // ---- links and build instructions ----

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-020 every relative link in the README and guide points to a file or directory of the repository")
    void relativeLinksResolve(Language language) {
        assertRelativeLinks(language.readme(), language.readmeFile());
        for (String file : language.guideFiles()) {
            assertRelativeLinks(Markdown.of(Repository.read(file)), file);
        }
    }

    private static void assertRelativeLinks(Markdown markdown, String document) {
        List<String> missing = markdown.links().stream()
                .filter(link -> !link.startsWith("http") && !link.startsWith("#") && !link.startsWith("mailto:"))
                .map(link -> link.contains("#") ? link.substring(0, link.indexOf('#')) : link)
                .filter(link -> !Repository.exists(Path.of(document).resolveSibling(link).normalize().toString())).toList();

        assertThat(missing).as("unresolved links in %s", document).isEmpty();
    }

    static Stream<Arguments> documentCases() {
        List<String> documents = new ArrayList<>(Repository.filesDirectlyIn("docs/release-notes").stream()
                .map(name -> "docs/release-notes/" + name).toList());
        documents.add(FORMAT);
        return languageAnd(documents);
    }

    @ParameterizedTest
    @MethodSource("documentCases")
    @DisplayName("DOC-021 the guide links every release note and the release note format")
    void readmeLinksTheReleaseNotes(Language language, String document) {
        assertThat(language.guide().links()).contains(document.substring("docs/".length()));
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("DOC-022 the README gives the build command (./gradlew build) and the JDK of the Gradle toolchain")
    void readmeGivesTheBuildInstructions(Language language) {
        Matcher toolchain = Pattern.compile("JavaLanguageVersion\\.of\\((\\d+)\\)").matcher(Repository.read("build.gradle.kts"));
        assertThat(toolchain.find()).isTrue();
        String text = language.guide().text();

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

    @Test
    @DisplayName("3.0.0 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v300NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v3 = Markdown.of(Repository.read("docs/release-notes/3.0.0.md"));
        assertThat(v3.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# agent-ledger-mcp 3.0.0");
        for (Language language : Language.values()) {
            Markdown part = v3.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("agent-ledger-mcp", "org.agentledger", "ala3",
                    "agent-ledger-mcp/attestation/v3", "agent-ledger-host-attestation", "agentledger.theme");
        }
        assertThat(v3.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v3.part(Language.EN.partHeading()).codeSpans());
        assertThat(v3.part(Language.KO.partHeading()).fences())
                .isEqualTo(v3.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.0.0 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v400NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v400 = Markdown.of(Repository.read("docs/release-notes/4.0.0.md"));
        assertThat(v400.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.0.0");
        for (Language language : Language.values()) {
            Markdown part = v400.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("Veridger", "veridger", "org.veridger", "veridger-host-attestation",
                    "veridger.theme", "AuthorAttestationVerifier", "ledger.mcp.token", "DeployFiles.replace",
                    "FileModes.apply", "umask", "0644", "DeployFilesUmaskTest");
        }
        assertThat(v400.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v400.part(Language.EN.partHeading()).codeSpans());
        assertThat(v400.part(Language.KO.partHeading()).fences())
                .isEqualTo(v400.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.0.1 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v401NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v401 = Markdown.of(Repository.read("docs/release-notes/4.0.1.md"));
        assertThat(v401.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.0.1");
        for (Language language : Language.values()) {
            Markdown part = v401.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("4.0.1", "McpToolName", "CodexDialect", "ClaudeDialect",
                    "AntigravityDialect", "HOST_ATTESTATION_REQUIRED");
        }
        assertThat(v401.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v401.part(Language.EN.partHeading()).codeSpans());
        assertThat(v401.part(Language.KO.partHeading()).fences())
                .isEqualTo(v401.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.1.0 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v410NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v410 = Markdown.of(Repository.read("docs/release-notes/4.1.0.md"));
        assertThat(v410.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.1.0");
        for (Language language : Language.values()) {
            Markdown part = v410.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("4.1.0", "Schema-Version: V11", "SessionAuthorInput",
                    "deploy/install-hook.sh", "deploy/veridger-updater.sh", "SHA256SUMS", "/updates", "ROLLED_BACK");
        }
        assertThat(v410.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v410.part(Language.EN.partHeading()).codeSpans());
        assertThat(v410.part(Language.KO.partHeading()).fences())
                .isEqualTo(v410.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.2.0 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v420NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v420 = Markdown.of(Repository.read("docs/release-notes/4.2.0.md"));
        assertThat(v420.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.2.0");
        for (Language language : Language.values()) {
            Markdown part = v420.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("4.2.0", "Schema-Version: V12", "V12__performance_indexes.sql",
                    "AttachmentStore.stream", "IdAllocator.reserveRange", "CommonCodeRepository",
                    "spring.threads.virtual.enabled", "ledger-cli migrate");
        }
        assertThat(v420.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v420.part(Language.EN.partHeading()).codeSpans());
        assertThat(v420.part(Language.KO.partHeading()).fences())
                .isEqualTo(v420.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.3.0 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v430NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v430 = Markdown.of(Repository.read("docs/release-notes/4.3.0.md"));
        assertThat(v430.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.3.0");
        for (Language language : Language.values()) {
            Markdown part = v430.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("4.3.0", "Schema-Version: V12", "taskId", "Labels",
                    "GET /criteria/detail", "GET /history", "/api/overview");
        }
        assertThat(v430.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v430.part(Language.EN.partHeading()).codeSpans());
        assertThat(v430.part(Language.KO.partHeading()).fences())
                .isEqualTo(v430.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.3.1 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v431NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v431 = Markdown.of(Repository.read("docs/release-notes/4.3.1.md"));
        assertThat(v431.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.3.1");
        for (Language language : Language.values()) {
            Markdown part = v431.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("4.3.1", "Schema-Version: V13", "V13__owner_passkey_counter.sql",
                    "BoundedStreamableServerTransportProvider", "TransitionOperation", "<etc>/driver-state",
                    "veridger-updater.sh", "veridger-updater.cmd");
        }
        assertThat(v431.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v431.part(Language.EN.partHeading()).codeSpans());
        assertThat(v431.part(Language.KO.partHeading()).fences())
                .isEqualTo(v431.part(Language.EN.partHeading()).fences());
    }

    @Test
    @DisplayName("4.3.2 release note follows RELEASE_NOTES_FORMAT.md in both languages with identical code spans and code blocks")
    void v432NoteFollowsFormatAndMatchesBothLanguages() {
        Markdown v432 = Markdown.of(Repository.read("docs/release-notes/4.3.2.md"));
        assertThat(v432.lines().stream().filter(line -> !line.isBlank()).findFirst().orElse(""))
                .isEqualTo("# veridger 4.3.2");
        for (Language language : Language.values()) {
            Markdown part = v432.part(language.partHeading());
            List<String> expected = format().part(language.partHeading()).tables().getFirst().firstCellNames();
            List<Markdown.Subsection> sections = part.subsections();
            assertThat(sections.stream().map(Markdown.Subsection::heading)).containsExactlyElementsOf(expected);
            assertThat(sections).allSatisfy(section -> assertThat(section.body()).as(section.heading()).isNotEmpty());
            assertThat(part.hasHangul()).isEqualTo(language.writtenInHangul());
            assertThat(part.text()).contains("4.3.2", "Schema-Version: V13", "ledger_whoami", "can_verify_login",
                    "ledger_review", "ledger_reviewer");
        }
        assertThat(v432.part(Language.KO.partHeading()).codeSpans())
                .isEqualTo(v432.part(Language.EN.partHeading()).codeSpans());
        assertThat(v432.part(Language.KO.partHeading()).fences())
                .isEqualTo(v432.part(Language.EN.partHeading()).fences());
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("FUP-INS-02 the Windows argument table of each README starts with the optional --stdin, and the header of install.cmd shows it first in its usage line")
    void theWindowsArgumentsShowTheStdinFlag(Language language) {
        List<String> rows = language.guide().prose().stream().filter(line -> line.startsWith("| `")).toList();
        int stdin = rows.indexOf(rows.stream().filter(row -> row.startsWith("| `[--stdin]` |")).findFirst().orElseThrow());
        int jars = rows.indexOf(rows.stream().filter(row -> row.startsWith("| `<jars-dir>` |")).findFirst().orElseThrow());

        assertThat(stdin).isEqualTo(jars - 1);
        assertThat(Repository.read("deploy/install.cmd")).contains("rem   deploy\\install.cmd [--stdin] <jars-dir> ");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("PCS-051 the READMEs and the header of install.cmd say that the secrets of Windows are the installing user's with read for LocalService, that nothing is handed over, and that the installer is run again as the same user; none says that another administrator is kept out")
    void pcs051_theWindowsSecretsAreTheInstallingUsersWithReadForLocalService(Language language) {
        String readme = language.guide().text();

        assertThat(readme).contains("LocalService").contains(language == Language.EN ? "same user" : "같은 사용자");
        assertThat(readme).contains(language == Language.EN ? "Nothing is handed over to LocalService" : "LocalService에 넘기는 것은 없으므로");
        assertThat(readme.toLowerCase(java.util.Locale.ROOT)).doesNotContain("another administrator");
        assertThat(Repository.read("deploy/install.cmd")).contains("Run it again as the same user").contains("nothing is handed over");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("SPL-009 the READMEs and the 2.0.0 note tell the folders of the secrets (name, who reads, folder), the key folder beside the secret directory, that the file permissions do not separate the two services (one operating system user), and that the per-client lock settings are gone; the headers of both installers name the folders")
    void spl009_theSecretFoldersAreTold(Language language) {
        String readme = language.guide().text();

        assertThat(language.guide().codeSpans()).contains("common", "admin", "mcp", "tool", "<settings folder>/secrets",
                "<settings folder>/attachment-keys", "UNKNOWN_SECRET_NAME", "ledger.jdbc.password", "ledger.admin.token",
                "ledger.admin.agent-token", "ledger.mcp.token", "ledger.import.source.password");
        assertThat(readme).doesNotContain("secrets/attachment-keys").doesNotContain("lockout.max-failures")
                .doesNotContain("lockout.lock-period");
        assertThat(readme).contains(language == Language.EN ? "one operating system user" : "같은 운영체제 사용자");
        String note = note().part(language.partHeading()).text();
        assertThat(note).contains("D-0031").contains("D-0013").contains("attachment-keys");
        assertThat(Repository.read("deploy/install.sh")).contains("(common, admin, mcp, tool)");
        assertThat(Repository.read("deploy/install.cmd")).contains("(common, admin, mcp, tool)");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("LGN-014 the upgrade guide of each README and the 2.0.0 note say that the admin token of 1.x is not imported (the installer's token is the one of 2.0), none tells to remove and make it again, and the security text ties the 256 bits to a token that token-generate made")
    void lgn014_theAdminTokenIsNotImported(Language language) {
        String readme = language.guide().text();
        String heading = language == Language.EN ? "The admin token is not imported" : "관리 토큰은 가져오지 않습니다";

        assertThat(language.guide().codeSpans()).contains("PGLEDGER_ADMIN_TOKEN", "token-generate", "SecureRandom", "ledger.admin.token");
        assertThat(readme).contains(heading);
        assertThat(readme.indexOf(heading)).as("in the step of import-secrets-1x").isGreaterThan(readme.indexOf(language == Language.EN
                ? "Move the secrets of 1.x with" : "로 1.x의 비밀을 옮깁니다"));
        assertThat(readme).doesNotContain("token-generate ledger.admin.token --print").doesNotContain(language == Language.EN
                ? "Generate the admin token again" : "관리 토큰을 다시 만듭니다");
        assertThat(readme).doesNotContain(language == Language.EN ? "the admin token is 256 bits from" : "관리 토큰은 `SecureRandom` 256비트");
        String note = note().part(language.partHeading()).text();
        assertThat(note).contains("PGLEDGER_ADMIN_TOKEN").contains(language == Language.EN ? "not imported" : "가져오지 않습니다");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("PCS-050 the README defines the personal edition (one person, their own computer or server, the whole installation target trusted, loopback only, the network exposure the operator's) and names no reverse proxy, certificate authority, nginx, ACME, tls-issue, tls-renew or seal-host")
    void theReadmeDefinesThePersonalEditionAndNamesNoProxyProduct(Language language) {
        String readme = language.guide().text();

        assertThat(readme).contains(language == Language.EN ? "personal edition" : "개인판").contains("127.0.0.1");
        assertThat(readme.toLowerCase(java.util.Locale.ROOT)).doesNotContain("nginx", "acme", "let's encrypt", "certbot", "caddy", "traefik",
                "cloudflare", "tls-issue", "tls-renew", "seal-host", "--tls-acme", "--nginx-conf", "--tls-certificate", "--tls-key");
    }

    @ParameterizedTest
    @MethodSource("clientCases")
    @DisplayName("v4 client plugin READMEs (EN and KO) describe direct host stamping without per-session key registration or token issuance")
    void clientReadmesDescribeDirectHostStamping(Language language, String directory) {
        String file = directory + "/" + (language == Language.EN ? "README.md" : "README.ko.md");
        String text = Repository.read(file);

        assertThat(text).as(file)
                .contains("ledger.mcp.token")
                .contains("HOST_ATTESTATION_REQUIRED")
                .doesNotContain("key-register")
                .doesNotContain("session-key")
                .doesNotContain("LEDGER_HOOK_KEY_DIR");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("R9-D0095 f the README of each language says that the line of --stdin is UTF-8 and how Windows PowerShell 5.1 pipes text ($OutputEncoding: ASCII unless changed; UTF-8 without a byte order mark)")
    void r9d0095_theReadmeSaysHowAStdinSecretIsEncoded(Language language) {
        String readme = language.guide().text();

        assertThat(readme).contains("$OutputEncoding", "UTF8Encoding]::new($false)", "INVALID_ENCODING");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("R9-D0089 the README of each language names the settings directory of each host as the default of ledger.secrets.home (/etc/<name> on Linux, C:/ProgramData/<name>/etc on Windows)")
    void r9d0089_theReadmeNamesTheDefaultOfEachHost(Language language) {
        String readme = language.guide().text();

        assertThat(readme).contains("`/etc/veridger`", "`C:/ProgramData/veridger/etc`");
        assertThat(readme).doesNotContain("(the Windows installer gives its own)").doesNotContain("(Windows 설치기는 자기 것을 지정합니다)");
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("R9-D0090 the README of each language says that the programs set their own JVM to UTC, so a command needs no -Duser.timezone")
    void r9d0090_theReadmeSaysTheProgramsPinUtc(Language language) {
        String readme = language.guide().text();

        assertThat(readme).contains("-Duser.timezone=UTC");
    }
}
