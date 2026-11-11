package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pgledger.hook.HookHarness.MAPPER;
import static org.pgledger.hook.HookHarness.WRITE_TOOLS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/** HK-010: the client manifests call the jar with the right argument, their matchers select exactly the ten write tools. */
class ManifestTest {

    private static final Path CLIENTS = Path.of(System.getProperty("ledger.hook.clients"));
    private static final Path STAGING = Path.of(System.getProperty("ledger.hook.staging"));
    private static final Pattern JAVA_COMMAND = Pattern.compile("^(?:cmd\\.exe /d /c )?java -jar (?:\"([^\"]+)\"|(\\S+)) (\\S+)$");
    private static final List<String> SERVERS = List.of("ledger", "test", "t0001sink", "t0001sink_isolated-01", "codex_apps");
    private static final List<String> READ_OPS = List.of("get", "history", "list", "search", "defects", "save_extra");

    private static JsonNode read(String relative) throws IOException {
        return MAPPER.readTree(Files.readString(CLIENTS.resolve(relative)));
    }

    /** The one command entry of a client: the node holding {@code command}, and the matcher in front of it. */
    private record Entry(String matcher, JsonNode command) {}

    private static Entry entry(Client client) throws IOException {
        return switch (client) {
            case CLAUDE -> entryOf(read("claude/hooks/hooks.json").path("hooks").path("PreToolUse"));
            case CODEX -> entryOf(read("codex/hooks/hooks.json").path("hooks").path("PreToolUse"));
            case ANTIGRAVITY -> entryOf(read("antigravity/hooks.json").path("pg-ledger-host-attestation").path("PreToolUse"));
        };
    }

    private static Entry entryOf(JsonNode preToolUse) {
        assertThat(preToolUse.size()).as("exactly one PreToolUse entry").isEqualTo(1);
        JsonNode first = preToolUse.get(0);
        assertThat(first.path("hooks").size()).isEqualTo(1);
        return new Entry(first.path("matcher").stringValue(), first.path("hooks").get(0));
    }

    private static List<String> writeNames(Client client) {
        List<String> names = new ArrayList<>();
        for (String op : WRITE_TOOLS) {
            switch (client) {
                case CLAUDE, CODEX -> {
                    for (String server : SERVERS) {
                        names.add("mcp__" + server + "__ledger_" + op);
                        names.add("mcp__" + server + "__app_ledger_ledger_" + op);
                    }
                }
                case ANTIGRAVITY -> {
                    names.addAll(List.of("mcp:ledger_" + op, "mcp:test:ledger_" + op, "mcp:test/ledger_" + op, "mcp:test_ledger_" + op));
                    names.add("mcp:t0001sink_isolated-01:app_ledger_ledger_" + op);
                }
            }
        }
        return names;
    }

    private static List<String> readNames(Client client) {
        List<String> names = new ArrayList<>();
        for (String op : READ_OPS) {
            switch (client) {
                case CLAUDE, CODEX -> SERVERS.forEach(server -> {
                    names.add("mcp__" + server + "__ledger_" + op);
                    names.add("mcp__" + server + "__app_ledger_ledger_" + op);
                });
                case ANTIGRAVITY -> names.addAll(List.of("mcp:ledger_" + op, "mcp:test:ledger_" + op, "mcp:test/ledger_" + op,
                        "mcp:test_ledger_" + op));
            }
        }
        return names;
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-010 the matcher matches all ten write tools in every naming variant and none of the read tools")
    void matcherSelectsTheWriteTools(Client client) throws IOException {
        Pattern matcher = Pattern.compile(entry(client).matcher());
        for (String name : writeNames(client)) {
            assertThat(matcher.matcher(name).find()).as(name).isTrue();
        }
        for (String name : readNames(client)) {
            assertThat(matcher.matcher(name).find()).as(name).isFalse();
        }
        if (client == Client.ANTIGRAVITY) {
            // the wrapper hides the tool name from the matcher; the hook itself decides (HK-012)
            assertThat(matcher.matcher("call_mcp_tool").find()).isTrue();
        }
        for (String name : List.of("Bash", "mcp__other__read", "ledger_save")) {
            assertThat(matcher.matcher(name).find()).as(name).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-010 every command is java -jar <path>/ledger-client-hook.jar <client>, and the path exists in the staged plugin directory")
    void commandsCallTheJar(Client client) throws IOException {
        JsonNode command = entry(client).command();
        List<String> commands = new ArrayList<>();
        commands.add(command.path("command").stringValue());
        if (command.has("commandWindows")) {
            commands.add(command.path("commandWindows").stringValue());
        }
        Path root = STAGING.resolve(client.argument());
        for (String text : commands) {
            Matcher m = JAVA_COMMAND.matcher(text);
            assertThat(m.matches()).as(text).isTrue();
            assertThat(m.group(3)).as("argument of " + text).isEqualTo(client.argument());
            String path = m.group(1) != null ? m.group(1) : m.group(2);
            String resolved = path.replace("${CLAUDE_PLUGIN_ROOT}", root.toString()).replace("${PLUGIN_ROOT}", root.toString())
                    .replace("%PLUGIN_ROOT%", root.toString());
            Path jar = resolved.startsWith("./") ? root.resolve(resolved.substring(2)) : Path.of(resolved);
            assertThat(jar.getFileName().toString()).isEqualTo("ledger-client-hook.jar");
            assertThat(jar).as(text).isRegularFile();
        }
        assertThat(command.path("type").stringValue()).isEqualTo("command");
        assertThat(command.path("timeout").intValue()).isBetween(5, 30);
    }

    @Test
    @DisplayName("HK-010 the path variables are each host's own: CLAUDE_PLUGIN_ROOT, PLUGIN_ROOT / %PLUGIN_ROOT%, relative for Antigravity")
    void pathVariables() throws IOException {
        assertThat(entry(Client.CLAUDE).command().path("command").stringValue()).contains("${CLAUDE_PLUGIN_ROOT}/lib/ledger-client-hook.jar");
        JsonNode codex = entry(Client.CODEX).command();
        assertThat(codex.path("command").stringValue()).contains("${PLUGIN_ROOT}/lib/ledger-client-hook.jar");
        assertThat(codex.path("commandWindows").stringValue()).startsWith("cmd.exe /d /c java -jar").contains("%PLUGIN_ROOT%/lib/ledger-client-hook.jar");
        assertThat(entry(Client.ANTIGRAVITY).command().path("command").stringValue()).contains("./lib/ledger-client-hook.jar");
    }

    @Test
    @DisplayName("HK-010 plugin names are unchanged and the manifests and READMEs no longer mention Node or .mjs")
    void pluginNamesAndNoNode() throws IOException {
        assertThat(read("claude/.claude-plugin/plugin.json").path("name").stringValue()).isEqualTo("pg-ledger-mcp");
        assertThat(read("codex/plugin.json").path("name").stringValue()).isEqualTo("pg-ledger-host-attestation");
        assertThat(read("antigravity/plugin.json").path("name").stringValue()).isEqualTo("pg-ledger-host-attestation");
        try (Stream<Path> files = Stream.of("claude/hooks/hooks.json", "claude/.claude-plugin/plugin.json", "claude/README.md", "codex/hooks/hooks.json",
                "codex/plugin.json", "codex/README.md", "antigravity/hooks.json", "antigravity/plugin.json", "antigravity/README.md")
                .map(CLIENTS::resolve)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String text = Files.readString(file);
                assertThat(text).as(file.toString()).doesNotContain(".mjs").doesNotContain("node ").doesNotContain("Node.js");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    @DisplayName("HK-010 every client README names the JDK 25 PATH requirement and the closed failure (HOST_ATTESTATION_REQUIRED)")
    void readmesStateRequirements(Client client) throws IOException {
        String readme = Files.readString(CLIENTS.resolve(client.argument() + "/README.md"));
        assertThat(readme).contains("JDK 25").contains("PATH").contains("HOST_ATTESTATION_REQUIRED")
                .contains("java -jar").contains("lib/ledger-client-hook.jar");
    }

    @Test
    @DisplayName("HK-010 the staged plugin directories carry the manifests and the jar, and no .mjs")
    void stagedDirectories() throws IOException {
        for (Client client : Client.values()) {
            Path root = STAGING.resolve(client.argument());
            assertThat(root.resolve("lib/ledger-client-hook.jar")).isRegularFile();
            try (Stream<Path> all = Files.walk(root)) {
                assertThat(all.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".mjs"));
            }
        }
        assertThat(STAGING.resolve("claude/hooks/hooks.json")).isRegularFile();
        assertThat(STAGING.resolve("claude/.claude-plugin/plugin.json")).isRegularFile();
        assertThat(STAGING.resolve("codex/hooks/hooks.json")).isRegularFile();
        assertThat(STAGING.resolve("antigravity/hooks.json")).isRegularFile();
    }
}
