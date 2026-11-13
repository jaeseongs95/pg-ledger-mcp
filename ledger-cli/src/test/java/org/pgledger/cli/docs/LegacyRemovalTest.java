package org.pgledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FUP-1X-01 .. FUP-1X-03, FUP-DOC-01 and FUP-DOC-02: the 1.x implementation (shell and Node programs, web files, DBHub
 * settings, SQL files) is not in the tree any more, no mention of one of its files leaves out that it is a file of 1.x, and the
 * READMEs and the release note say where the 1.x source is (the tag v1.1.0). The repository root is the one DocsTest reads.
 */
class LegacyRemovalTest {

    /** The tag that keeps the 1.x source. */
    private static final String TAG = "v1.1.0";
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".gradle", "build", "node_modules");
    private static final Pattern SCRIPT_FILE = Pattern.compile("(?i).*\\.(mjs|cjs|js|sh|cmd|ps1|py)$");
    /** A path of a 1.x file: below one of the directories of 1.x and ending in a file name of its kinds, or a program of {@code bin/}. */
    private static final Pattern ONE_X_PATH = Pattern.compile(
            "(?<![\\w/.-])(?:(?:sql|tests|web|mcp|bin)/[A-Za-z0-9_*.-]+\\.(?:sql|json|sh|mjs|js|css|html|toml)|bin/ledger-admin\\S*|bin/mcp-call\\S*|tests/\\*\\.\\w+)");
    private static final String THIS_FILE = "LegacyRemovalTest.java";

    @ParameterizedTest
    @ValueSource(strings = { "bin/ledger-admin", "bin/ledger-admin-server.mjs", "bin/mcp-call.mjs", "web/app.js",
            "web/index.html", "web/styles.css", "mcp/dbhub.toml", "sql/10_tables.sql", "sql/15_defect_case.sql",
            "sql/16_common_code.sql", "sql/17_created_by.sql", "sql/20_api.sql", "tests/admin-ui.mjs",
            "tests/claude-hook.mjs", "tests/e2e.mjs", "tests/e2e.sh", "tests/host-attestation.mjs",
            "tests/upgrade-atomicity.sh", "tests/category-legacy-fixture.json",
            "clients/claude/hooks/host-attestation.mjs", "clients/codex/scripts/ledger-host-attestation.mjs",
            "clients/antigravity/scripts/ledger-host-attestation.mjs", "bin", "web", "mcp", "sql", "tests" })
    @DisplayName("FUP-1X-01 no file of the 1.x implementation and none of its five directories is in the tree")
    void theOneXFilesAreGone(String path) {
        assertThat(Repository.exists(path)).as(path).isFalse();
    }

    @Test
    @DisplayName("FUP-1X-02 the tree holds no Node file (.mjs, .cjs, package.json) and no script below clients/")
    void noNodeFilesAndNoClientScripts() throws IOException {
        Path root = Repository.resolve(".").normalize();
        List<String> nodeFiles;
        List<String> clientScripts;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).filter(file -> !inSkippedDirectory(root, file)).toList();
            nodeFiles = files.stream().map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("(?i).*\\.(mjs|cjs)$") || "package.json".equals(name)).toList();
            clientScripts = files.stream().filter(file -> root.relativize(file).startsWith("clients"))
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> SCRIPT_FILE.matcher(name).matches()).toList();
        }

        assertThat(nodeFiles).isEmpty();
        assertThat(clientScripts).isEmpty();
    }

    @Test
    @DisplayName("FUP-1X-03 every mention of a file of 1.x in a source (javadoc, comment, display name) says on its line that it is of 1.x")
    void mentionsOfOneXFilesSayOneX() throws IOException {
        Path root = Repository.resolve(".").normalize();
        List<String> offending = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).filter(m -> m.getFileName().toString().startsWith("ledger-"))
                    .toList()) {
                Path sources = module.resolve("src");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(sources)) {
                    for (Path file : walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java"))
                            .filter(f -> !THIS_FILE.equals(f.getFileName().toString())).toList()) {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            if (ONE_X_PATH.matcher(line).find() && !line.contains("1.x")) {
                                offending.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1) + " " + line.strip());
                            }
                        }
                    }
                }
            }
        }

        assertThat(offending).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("FUP-DOC-01 the README says that the 1.x implementation is not in the tree and that its source is the tag v1.1.0; the Layout does not keep it any more")
    void theReadmeNamesTheTagOfTheOneXSource(Language language) {
        List<String> lines = language.readme().prose();

        assertThat(lines).anyMatch(line -> line.contains("1.x") && line.contains("`" + TAG + "`"));
        assertThat(lines).noneMatch(line -> line.contains("1.x") && line.contains("`bin/`"));
        assertThat(lines).noneMatch(line -> line.contains("tests/*.mjs"));
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    @DisplayName("FUP-DOC-02 the Removed section of the 2.0.0 note says in each language that the 1.x implementation of the tree is removed and that the tag v1.1.0 has it")
    void theNoteListsTheRemoval(Language language) {
        String removed = language.sections().get(5);
        Markdown.Subsection section = Markdown.of(Repository.read("docs/release-notes/2.0.0.md"))
                .part(language.partHeading()).subsections().stream().filter(s -> removed.equals(s.heading())).findFirst()
                .orElseThrow();

        assertThat(section.body()).anyMatch(line -> line.contains("`" + TAG + "`") && line.contains("`bin/`")
                && line.contains("`web/`") && line.contains("`mcp/`") && line.contains("`sql/`") && line.contains("`tests/`"));
    }

    private static boolean inSkippedDirectory(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
