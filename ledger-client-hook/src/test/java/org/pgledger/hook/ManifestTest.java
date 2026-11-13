package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** HK-010: the client manifests call the jar with the right argument, their matchers select exactly the ten write tools. */
class ManifestTest {

    private static final Path CLIENTS = Path.of(System.getProperty("ledger.hook.clients"));
    private static final Path STAGING = Path.of(System.getProperty("ledger.hook.staging"));
    private static final Pattern JAVA_COMMAND = Pattern.compile("^(?:cmd\\.exe /d /c )?java -jar (?:\"([^\"]+)\"|(\\S+)) (\\S+)$");
    private static final List<String> SERVERS = List.of("ledger", "test", "t0001sink", "t0001sink_isolated-01", "codex_apps");
    private static final List<String> READ_OPS = List.of("get", "history", "list", "search", "defects", "save_extra");

    /** Where each client keeps its PreToolUse entries: the hooks file and the JSON pointer inside it. */
    private record HooksFile(String file, String pointer) {}

    private static final Map<Client, HooksFile> HOOKS = Map.of(
            Client.CLAUDE, new HooksFile("claude/hooks/hooks.json", "/hooks/PreToolUse"),
            Client.CODEX, new HooksFile("codex/hooks/hooks.json", "/hooks/PreToolUse"),
            Client.ANTIGRAVITY, new HooksFile("antigravity/hooks.json", "/pg-ledger-host-attestation/PreToolUse"));

    private static JsonNode read(String relative) throws IOException {
        return Json.of(Files.readString(CLIENTS.resolve(relative)));
    }

    /** The one hook entry of a client. */
    private static JsonNode entry(Client client) throws IOException {
        HooksFile hooks = HOOKS.get(client);
        JsonNode preToolUse = read(hooks.file()).at(hooks.pointer());
        assertThat(preToolUse.size()).as("exactly one PreToolUse entry").isEqualTo(1);
        assertThat(preToolUse.get(0).path("hooks").size()).as("exactly one hook in the entry").isEqualTo(1);
        return preToolUse.get(0);
    }

    private static JsonNode command(Client client) throws IOException {
        return entry(client).path("hooks").get(0);
    }

    /**
     * Host tool names in every naming variant of that host that the hook signs for (HK-032): the server segment, and
     * the same segment as the prefix of a gateway.
     */
    private static Stream<String> names(Client client, String op) {
        return switch (client) {
            case CLAUDE -> SERVERS.stream().flatMap(server -> Stream.of(
                    "mcp__" + server + "__ledger_" + op, "mcp__" + server + "__" + server + "_ledger_" + op));
            case CODEX -> Stream.concat(SERVERS.stream().flatMap(server -> Stream.of(
                    "mcp__" + server + "__ledger_" + op, "mcp__" + server + "__" + server + "_ledger_" + op)),
                    Stream.of("mcp__codex_apps__app_ledger_ledger_" + op));
            case ANTIGRAVITY -> Stream.of("mcp:test:ledger_" + op, "mcp:test/ledger_" + op, "mcp:test_ledger_" + op,
                    "mcp:ledger_ledger_" + op, "mcp:t0001sink_isolated-01:t0001sink_isolated-01_ledger_" + op);
        };
    }

    /** (client, tool name, whether the matcher must select it) */
    static Stream<Arguments> matcherCases() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(
                        Hosts.WRITE_OPS.stream().flatMap(op -> names(client, op)).map(name -> Map.entry(name, true)),
                        READ_OPS.stream().flatMap(op -> names(client, op)).map(name -> Map.entry(name, false)),
                        Stream.of("Bash", "mcp__other__read", "ledger_save", "mcp__ledger_save", "mcp:ledger_save",
                                "mcp__ledger__ledger_save_extra", "xmcp__ledger__ledger_save")
                                .map(name -> Map.entry(name, false)))
                .flatMap(cases -> cases)
                .map(c -> Arguments.argumentSet(client + " " + c.getKey(), client, c.getKey(), c.getValue())));
    }

    @ParameterizedTest
    @MethodSource("matcherCases")
    @DisplayName("HK-010 the matcher matches each write tool in every naming variant that the hook signs for (the structural shape server + [prefix] + ledger_<op>) and none of the read tools, names without a server or other tools")
    void matcherSelectsTheWriteTools(Client client, String name, boolean selected) throws IOException {
        Pattern matcher = Pattern.compile(entry(client).path("matcher").stringValue());
        assertThat(matcher.matcher(name).find()).isEqualTo(selected);
    }

    @Test
    @DisplayName("HK-010 Antigravity: the wrapper hides the tool name from the matcher, so call_mcp_tool is selected and the hook decides (HK-012)")
    void antigravityMatcherSelectsTheWrapper() throws IOException {
        Pattern matcher = Pattern.compile(entry(Client.ANTIGRAVITY).path("matcher").stringValue());
        assertThat(matcher.matcher("call_mcp_tool").find()).isTrue();
    }

    /** (client, name of the command field): Codex has a separate Windows command. */
    static Stream<Arguments> commandFields() {
        return Stream.of(
                Arguments.of(Client.CLAUDE, "command"),
                Arguments.of(Client.CODEX, "command"),
                Arguments.of(Client.CODEX, "commandWindows"),
                Arguments.of(Client.ANTIGRAVITY, "command"));
    }

    @ParameterizedTest
    @MethodSource("commandFields")
    @DisplayName("HK-010 every command is java -jar <path>/ledger-client-hook.jar <client>, and the path exists in the staged plugin directory")
    void commandsCallTheJar(Client client, String field) throws IOException {
        String text = command(client).path(field).stringValue();
        Matcher m = JAVA_COMMAND.matcher(text);
        assertThat(m.matches()).as(text).isTrue();
        assertThat(m.group(3)).as("argument of " + text).isEqualTo(client.argument());
        Path root = STAGING.resolve(client.argument());
        String path = m.group(1) != null ? m.group(1) : m.group(2);
        String resolved = path.replace("${CLAUDE_PLUGIN_ROOT}", root.toString()).replace("${PLUGIN_ROOT}", root.toString())
                .replace("%PLUGIN_ROOT%", root.toString());
        Path jar = resolved.startsWith("./") ? root.resolve(resolved.substring(2)) : Path.of(resolved);
        assertThat(jar.getFileName().toString()).isEqualTo("ledger-client-hook.jar");
        assertThat(jar).as(text).isRegularFile();
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-010 every hook is a command with a timeout between 5 and 30 seconds")
    void commandTypeAndTimeout(Client client) throws IOException {
        JsonNode command = command(client);
        assertThat(command.path("type").stringValue()).isEqualTo("command");
        assertThat(command.path("timeout").intValue()).isBetween(5, 30);
    }

    /** (client, command field, text the command must contain): the path variables are each host's own. */
    static Stream<Arguments> pathVariables() {
        return Stream.of(
                Arguments.of(Client.CLAUDE, "command", "${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar"),
                Arguments.of(Client.CODEX, "command", "${PLUGIN_ROOT}/lib/ledger-client-hook.jar"),
                Arguments.of(Client.CODEX, "commandWindows", "%PLUGIN_ROOT%/lib/ledger-client-hook.jar"),
                Arguments.of(Client.ANTIGRAVITY, "command", "./lib/ledger-client-hook.jar"));
    }

    @ParameterizedTest
    @MethodSource("pathVariables")
    @DisplayName("HK-010 the path variables are each host's own: CLAUDE_PLUGIN_ROOT, PLUGIN_ROOT / %PLUGIN_ROOT%, relative for Antigravity")
    void pathVariablesAreTheHostsOwn(Client client, String field, String expected) throws IOException {
        assertThat(command(client).path(field).stringValue()).contains(expected);
    }

    @Test
    @DisplayName("HK-010 Codex on Windows starts java through cmd.exe /d /c")
    void codexWindowsCommandUsesCmd() throws IOException {
        assertThat(command(Client.CODEX).path("commandWindows").stringValue()).startsWith("cmd.exe /d /c java -jar");
    }

    static Stream<Arguments> pluginNames() {
        return Stream.of(
                Arguments.of("claude/.claude-plugin/plugin.json", "pg-ledger-mcp"),
                Arguments.of("codex/plugin.json", "pg-ledger-host-attestation"),
                Arguments.of("antigravity/plugin.json", "pg-ledger-host-attestation"));
    }

    @ParameterizedTest
    @MethodSource("pluginNames")
    @DisplayName("HK-010 plugin names are unchanged")
    void pluginNameIsUnchanged(String file, String name) throws IOException {
        assertThat(read(file).path("name").stringValue()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "claude/hooks/hooks.json", "claude/.claude-plugin/plugin.json", "claude/README.md", "claude/README.ko.md",
            "codex/hooks/hooks.json", "codex/plugin.json", "codex/README.md", "codex/README.ko.md",
            "antigravity/hooks.json", "antigravity/plugin.json", "antigravity/README.md", "antigravity/README.ko.md"})
    @DisplayName("HK-010 the manifests and READMEs no longer mention Node or .mjs")
    void noNode(String file) throws IOException {
        assertThat(Files.readString(CLIENTS.resolve(file))).doesNotContain(".mjs").doesNotContain("node ").doesNotContain("Node.js");
    }

    /** (client, README file name): the English README and the Korean one of every client. */
    static Stream<Arguments> readmes() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of("README.md", "README.ko.md")
                .map(name -> Arguments.argumentSet(client + " " + name, client, name)));
    }

    @ParameterizedTest
    @MethodSource("readmes")
    @DisplayName("HK-010 / FUP-CLI-02 every client README, in both languages, names the JDK 25 PATH requirement and the closed failure (HOST_ATTESTATION_REQUIRED)")
    void readmesStateRequirements(Client client, String name) throws IOException {
        String readme = Files.readString(CLIENTS.resolve(client.argument() + "/" + name));
        assertThat(readme).contains("JDK 25").contains("PATH").contains("HOST_ATTESTATION_REQUIRED")
                .contains("java -jar").contains("lib/ledger-client-hook.jar");
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("FUP-CLI-01 each client has README.md in English (no Hangul) that links README.ko.md, and README.ko.md in Korean (Hangul); there is no other README")
    void readmesAreEnglishAndKorean(Client client) throws IOException {
        Path directory = CLIENTS.resolve(client.argument());
        // the one Korean word of the English README is the text of the link to the Korean one
        String english = Files.readString(directory.resolve("README.md")).replace("[한국어](README.ko.md)", "[Korean](README.ko.md)");
        String korean = Files.readString(directory.resolve("README.ko.md"));

        assertThat(HANGUL.matcher(english).find()).as("English README has no Hangul").isFalse();
        assertThat(HANGUL.matcher(korean).find()).as("Korean README is Korean").isTrue();
        assertThat(english).contains("](README.ko.md)");
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).startsWith("readme")))
                    .containsExactlyInAnyOrder("README.md", "README.ko.md");
        }
    }

    @ParameterizedTest
    @MethodSource("readmes")
    @DisplayName("FUP-CLI-02 every README describes the 2.0 behaviour (Ed25519 session key, session-key, key-register, LEDGER_HOOK_KEY_DIR) and not the 1.x form (the host session id put into _hostAttestation unsigned)")
    void readmesDescribeTheSignedAttestation(Client client, String name) throws IOException {
        String readme = Files.readString(CLIENTS.resolve(client.argument() + "/" + name));
        assertThat(readme).contains("Ed25519").contains("session-key").contains("key-register").contains("LEDGER_HOOK_KEY_DIR")
                .contains("_hostAttestation");
        assertThat(readme).doesNotContain("claude:<id>").doesNotContain("codex:<id>").doesNotContain("gemini:<id>");
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("FUP-CLI-02 the English and the Korean README of a client have the same code spans (names, commands and keys are not translated)")
    void readmeCodeSpansAreTheSameInBothLanguages(Client client) throws IOException {
        Path directory = CLIENTS.resolve(client.argument());

        assertThat(codeSpans(Files.readString(directory.resolve("README.ko.md"))))
                .isEqualTo(codeSpans(Files.readString(directory.resolve("README.md"))));
    }

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u3131-\\u318E]");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]+)`");

    private static Set<String> codeSpans(String markdown) {
        Set<String> spans = new TreeSet<>();
        Matcher matcher = CODE_SPAN.matcher(markdown);
        while (matcher.find()) {
            spans.add(matcher.group(1));
        }
        return spans;
    }

    @ParameterizedTest
    @ValueSource(strings = { "claude/.claude-plugin/plugin.json", "codex/plugin.json", "antigravity/plugin.json" })
    @DisplayName("FUP-CLI-03 the version of every plugin is the version of the release, 2.0.0")
    void pluginVersionIsTheRelease(String file) throws IOException {
        assertThat(read(file).path("version").stringValue()).isEqualTo("2.0.0");
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-010 the staged plugin directory carries the jar and no .mjs")
    void stagedDirectoryHasTheJar(Client client) throws IOException {
        Path root = STAGING.resolve(client.argument());
        assertThat(root.resolve("lib/ledger-client-hook.jar")).isRegularFile();
        try (Stream<Path> all = Files.walk(root)) {
            assertThat(all.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".mjs"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "claude/hooks/hooks.json", "claude/.claude-plugin/plugin.json", "codex/hooks/hooks.json", "antigravity/hooks.json"})
    @DisplayName("HK-010 the staged plugin directories carry the manifests")
    void stagedDirectoryHasTheManifests(String file) {
        assertThat(STAGING.resolve(file)).isRegularFile();
    }
}
