package org.pgledger.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SET-005 and SET-022: the build of the seed bundle from its text files, and the digest the bundle is compared by. The file
 * is made and read back with the same classes the build and the program use; a bad source stops the build with a message that
 * names the file and the line, and no bundle is written.
 */
class SeedBuilderTest {

    private static final String HEADER = "code\tname_ko\tname_en\tdescription_ko\tdescription_en";
    private static final String GOOD_ROW = "AAA\t첫째\tFirst\t첫째 설명\tThe first one.";

    @TempDir
    Path directory;

    private Path sources() throws IOException {
        Path sources = Files.createDirectories(directory.resolve("src"));
        Files.writeString(sources.resolve(SeedSources.CATEGORIES), HEADER + "\n" + GOOD_ROW + "\nBBB\t둘째\tSecond\t둘째 설명\tThe second one.\n",
                StandardCharsets.UTF_8);
        Files.writeString(sources.resolve(SeedSources.DEFAULTS), "# a comment\ninstance-name=demo\nmcp-port=9000\n", StandardCharsets.UTF_8);
        return sources;
    }

    @Test
    @DisplayName("SET-005 the bundle that is built reads back as the text source says: the codes in file order with the sort order 10, 20, the defaults, the digest")
    void buildAndReadBack() throws IOException {
        Path output = directory.resolve("out");

        Path file = SeedBuilder.build(sources(), output);
        SeedBundle bundle = SeedBundle.ofFile(file);

        assertThat(file).isEqualTo(output.resolve(SeedBundle.RESOURCE)).isRegularFile();
        assertThat(bundle.codes()).containsExactly(
                new SeedCode("DEFECT_CATEGORY", "AAA", "첫째", "First", "첫째 설명", "The first one.", 10, "ACTIVE"),
                new SeedCode("DEFECT_CATEGORY", "BBB", "둘째", "Second", "둘째 설명", "The second one.", 20, "ACTIVE"));
        assertThat(bundle.installDefaults()).isEqualTo(Map.of("instance-name", "demo", "mcp-port", "9000"));
        assertThat(bundle.installDefault("mcp-port")).isEqualTo("9000");
        assertThat(bundle.digest()).isEqualTo(SeedDigest.of(bundle.codes())).hasSize(64);
        assertThatThrownBy(() -> bundle.installDefault("no-such")).isInstanceOf(SeedUnavailableException.class);
    }

    @Test
    @DisplayName("SET-005 a second build replaces the bundle instead of extending it")
    void rebuildReplaces() throws IOException {
        Path sources = sources();
        Path output = directory.resolve("out");
        SeedBuilder.build(sources, output);
        Files.writeString(sources.resolve(SeedSources.CATEGORIES), HEADER + "\n" + GOOD_ROW + "\n", StandardCharsets.UTF_8);

        SeedBuilder.build(sources, output);

        assertThat(SeedBundle.ofFile(output.resolve(SeedBundle.RESOURCE)).codes()).hasSize(1);
    }

    static Stream<Arguments> badSources() {
        return Stream.of(
                Arguments.of(HEADER + "\nAAA\t첫째\tFirst\t설명", "defect-categories.tsv:2", "5 columns"),
                Arguments.of(HEADER + "\n" + GOOD_ROW + "\n" + GOOD_ROW, "defect-categories.tsv:3", "twice"),
                Arguments.of(HEADER + "\nAAA\t\tFirst\t설명\tDescription", "defect-categories.tsv:2", "empty"),
                Arguments.of(HEADER + "\n1AA\t이름\tName\t설명\tDescription", "defect-categories.tsv:2", "code must be"),
                Arguments.of("code\tname\n" + GOOD_ROW, "defect-categories.tsv:1", "header"),
                Arguments.of(HEADER, "defect-categories.tsv:1", "no code"));
    }

    @ParameterizedTest
    @MethodSource("badSources")
    @DisplayName("SET-005 a bad category file stops the build, names the file and the line, and writes no bundle")
    void badCategoryFile(String content, String where, String reason) throws IOException {
        Path sources = sources();
        Files.writeString(sources.resolve(SeedSources.CATEGORIES), content, StandardCharsets.UTF_8);
        Path output = directory.resolve("out");

        assertThatThrownBy(() -> SeedBuilder.build(sources, output)).isInstanceOf(SeedUnavailableException.class)
                .hasMessageContaining(where).hasMessageContaining(reason);
        assertThat(output.resolve(SeedBundle.RESOURCE)).doesNotExist();
    }

    static Stream<Arguments> badDefaults() {
        return Stream.of(
                Arguments.of("instance name=demo", "install-defaults.properties:1", "name must be"),
                Arguments.of("instance-name=demo\ninstance-name=other", "install-defaults.properties:2", "twice"),
                Arguments.of("instance-name=", "install-defaults.properties:1", "empty"),
                Arguments.of("instance-name= demo", "install-defaults.properties:1", "white space"),
                Arguments.of("instance-name", "install-defaults.properties:1", "name=value"),
                Arguments.of("# only a comment", "install-defaults.properties:1", "no default"));
    }

    @ParameterizedTest
    @MethodSource("badDefaults")
    @DisplayName("SET-005 a bad defaults file (a name with a space, a name twice, an empty value, no value, nothing) stops the build the same way")
    void badDefaultsFile(String content, String where, String reason) throws IOException {
        Path sources = sources();
        Files.writeString(sources.resolve(SeedSources.DEFAULTS), content, StandardCharsets.UTF_8);
        Path output = directory.resolve("out");

        assertThatThrownBy(() -> SeedBuilder.build(sources, output)).isInstanceOf(SeedUnavailableException.class)
                .hasMessageContaining(where).hasMessageContaining(reason);
        assertThat(output.resolve(SeedBundle.RESOURCE)).doesNotExist();
    }

    @Test
    @DisplayName("SET-005 a missing source file, and a bundle file that is missing, stop with a message that names the file")
    void missingFiles() throws IOException {
        Path sources = sources();
        Files.delete(sources.resolve(SeedSources.DEFAULTS));

        assertThatThrownBy(() -> SeedBuilder.build(sources, directory.resolve("out"))).isInstanceOf(SeedUnavailableException.class)
                .hasMessageContaining(SeedSources.DEFAULTS);
        assertThatThrownBy(() -> SeedBundle.ofFile(directory.resolve("none").resolve(SeedBundle.RESOURCE)).codes())
                .isInstanceOf(SeedUnavailableException.class).hasMessageContaining(SeedBundle.RESOURCE);
    }

    private static SeedCode code(String code, String name, int sort, String state) {
        return new SeedCode("DEFECT_CATEGORY", code, name, "en " + name, "d " + name, "e " + name, sort, state);
    }

    @Test
    @DisplayName("SET-022 the digest does not depend on the order of the codes and changes with every field: a text, the state, the sort order, the group, the code")
    void theDigest() {
        List<SeedCode> codes = List.of(code("AAA", "a", 10, "ACTIVE"), code("BBB", "b", 20, "ACTIVE"));
        String digest = SeedDigest.of(codes);

        assertThat(SeedDigest.of(List.of(codes.get(1), codes.get(0)))).isEqualTo(digest);
        assertThat(digest).matches("[0-9a-f]{64}");
        for (SeedCode changed : List.of(code("AAA", "a!", 10, "ACTIVE"), code("AAA", "a", 11, "ACTIVE"), code("AAA", "a", 10, "INACTIVE"),
                code("AAB", "a", 10, "ACTIVE"), new SeedCode("RELATION_KIND", "AAA", "a", "en a", "d a", "e a", 10, "ACTIVE"),
                new SeedCode("DEFECT_CATEGORY", "AAA", "a", "en a", "d a", "e a!", 10, "ACTIVE"))) {
            assertThat(SeedDigest.of(List.of(changed, codes.get(1)))).isNotEqualTo(digest);
        }
        assertThat(SeedDigest.of(List.of())).isNotEqualTo(digest);
    }
}
