package org.pgledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.seed.InstallDefaults;
import org.pgledger.cli.seed.PackagedSeed;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.seed.SeedBundle;
import org.pgledger.seed.SeedCode;
import org.pgledger.seed.SeedSources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * SET-001 .. SET-004, SET-006, SET-015, SET-053: the text source of the seed, the bundle the build made from it, and what the
 * seed and the product SQL must not hold. The sources are read from the repository (the root is passed in by the build).
 */
class SeedTest {

    private static final List<String> CATEGORIES = List.of("ARC", "CON", "DAT", "DENY", "DIAG", "DOC", "EXC", "GATE", "HOST",
            "PKG", "RES", "SEC", "TST", "VND");
    private static final Path SOURCES = Path.of("ledger-cli", "src", "seed");
    /** A word that belongs to a secret, in a name or in a text of the seed. */
    private static final Pattern SECRET_WORD = Pattern.compile("(?i)pass(word|wd)|secret|token|credential|private[ -]?key|api[ -]?key");
    /** A provider, a cloud or a host of an operator's own environment: no name of one belongs in a public product. */
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("(?i)lightsail|cloudflare|amazon|\\baws\\b|azure|google|ssh-prod|ssh-test");
    private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile("[a-z]+://[^/\\s]*:[^@\\s]*@");
    private static final Pattern LONG_RANDOM = Pattern.compile("[A-Za-z0-9+/_=-]{32,}");

    private static Path source(String name) {
        return Repository.resolve(SOURCES.resolve(name).toString().replace('\\', '/'));
    }

    @Test
    @DisplayName("SET-001 the text source has exactly the 14 categories, each with both names and both descriptions, in the format and the limits of the ledger")
    void set001_theTextSource() {
        List<SeedCode> codes = SeedSources.codes(source(SeedSources.CATEGORIES));

        assertThat(codes.stream().map(SeedCode::code)).containsExactlyElementsOf(CATEGORIES);
        for (SeedCode code : codes) {
            // the constructors of the ledger check the format of the code and the length of the texts
            CommonCode ledgerCode = new CommonCode(CodeGroup.valueOf(code.group()), CodeValue.of(code.code()), code.nameKo(),
                    code.nameEn(), code.descriptionKo(), code.descriptionEn(), code.sortOrder(), CodeState.valueOf(code.state()),
                    AuthorId.OWNER);
            assertThat(ledgerCode.nameKo()).isEqualTo(code.nameKo());
            assertThat(ledgerCode.descriptionEn()).isEqualTo(code.descriptionEn());
            assertThat(code.nameKo()).isNotBlank();
            assertThat(code.nameEn()).isNotBlank();
            assertThat(code.descriptionKo()).isNotBlank();
            assertThat(code.descriptionEn()).isNotBlank();
        }
    }

    @Test
    @DisplayName("SET-002 the bundle of the build holds the same 14 codes and texts as the text source, active, sorted in the order of the file, and its digest is the digest of the text")
    void set002_theBundle() {
        List<SeedCode> text = SeedSources.codes(source(SeedSources.CATEGORIES));
        SeedBundle bundle = SeedBundle.packaged();

        assertThat(bundle.codes()).isEqualTo(text);
        assertThat(bundle.codes()).extracting(SeedCode::sortOrder).containsExactly(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110,
                120, 130, 140);
        assertThat(bundle.codes()).extracting(SeedCode::state).containsOnly("ACTIVE");
        assertThat(bundle.codes()).extracting(SeedCode::group).containsOnly(CodeGroup.DEFECT_CATEGORY.name());
        assertThat(bundle.digest()).hasSize(64).isEqualTo(org.pgledger.seed.SeedDigest.of(text));
    }

    @Test
    @DisplayName("SET-003 the bundle holds the install defaults and no other name: instance-name, mcp-port, admin-port")
    void set003_theInstallDefaults() {
        Map<String, String> defaults = SeedBundle.packaged().installDefaults();

        assertThat(defaults).containsOnlyKeys(InstallDefaults.NAMES.toArray(String[]::new));
        assertThat(defaults).isEqualTo(SeedSources.defaults(source(SeedSources.DEFAULTS)));
        InstallDefaults typed = new InstallDefaults(new PackagedSeed());
        assertThat(typed.instanceName()).isEqualTo("pg-ledger-mcp");
        assertThat(typed.mcpPort()).isEqualTo(8765);
        assertThat(typed.adminPort()).isEqualTo(8865);
    }

    private static List<String> everyText(SeedBundle bundle) throws IOException {
        List<String> texts = new ArrayList<>(bundle.installDefaults().keySet());
        texts.addAll(bundle.installDefaults().values());
        for (SeedCode code : bundle.codes()) {
            texts.addAll(List.of(code.group(), code.code(), code.nameKo(), code.nameEn(), code.descriptionKo(), code.descriptionEn(),
                    code.state()));
        }
        try (InputStream stream = new ClassPathResource(SeedBundle.RESOURCE).getInputStream()) {
            // the bytes of the file as they are: whatever is in it, text or not, is looked at
            texts.add(StreamUtils.copyToString(stream, StandardCharsets.ISO_8859_1));
        }
        return texts;
    }

    @Test
    @DisplayName("SET-004 the seed holds no secret and no name of an environment of its own: no secret word, no URL with credentials, no long random value, no provider, cloud or SSH host name")
    void set004_noSecretAndNoEnvironment() throws IOException {
        for (String text : everyText(SeedBundle.packaged())) {
            assertThat(SECRET_WORD.matcher(text).find()).as("secret word in %s", shortened(text)).isFalse();
            assertThat(ENVIRONMENT_NAME.matcher(text).find()).as("environment name in %s", shortened(text)).isFalse();
            assertThat(URL_WITH_CREDENTIALS.matcher(text).find()).as("credentials in %s", shortened(text)).isFalse();
        }
        for (String value : SeedBundle.packaged().installDefaults().values()) {
            assertThat(LONG_RANDOM.matcher(value).matches()).as("random value %s", value).isFalse();
        }
        assertThat(SeedBundle.packaged().installDefaults().keySet()).noneMatch(name -> SECRET_WORD.matcher(name).find());
    }

    private static String shortened(String text) {
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }

    @Test
    @DisplayName("SET-006 no seed bundle (no *.mv.db file) is in the source tree outside a build directory; the build of ledger-cli makes it from the text and packs it")
    void set006_theBinaryIsGeneratedAndNeverCommitted() throws IOException {
        Path root = Repository.resolve(".");
        List<Path> found = new ArrayList<>();
        Set<String> skipped = Set.of("build", ".gradle", ".git", "node_modules");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return skipped.contains(String.valueOf(directory.getFileName())) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().endsWith(".mv.db")) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(found).isEmpty();
        String build = Repository.read("ledger-cli/build.gradle.kts");
        assertThat(build).contains("generateSeed", "org.pgledger.seed.SeedBuilder", "from(generateSeed)");
    }

    @Test
    @DisplayName("SET-015 the product SQL has no statement that lets an account use a schema and names no DBMS, and the guidance of setup is generic in both languages")
    void set015_noDbmsSpecificSql() throws IOException {
        List<Path> sql = new ArrayList<>(Repository.files("ledger-database/src/main/resources", ".sql"));
        assertThat(sql).isNotEmpty();
        Pattern schemaUsage = Pattern.compile("(?i)GRANT\\s+USAGE|USAGE\\s+ON\\s+SCHEMA");
        for (Path file : sql) {
            assertThat(schemaUsage.matcher(Repository.read(file)).find()).as(file.toString()).isFalse();
        }
        Pattern dbms = Pattern.compile("(?i)postgres|mysql|mariadb|oracle|sql server|h2|sqlite|db2");
        for (String bundle : List.of("messages.properties", "messages_ko.properties")) {
            java.util.Properties texts = new java.util.Properties();
            try (InputStream stream = new ClassPathResource(bundle).getInputStream()) {
                texts.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            assertThat(dbms.matcher(texts.getProperty("error.APP_ACCOUNT_NO_ACCESS")).find()).as(bundle).isFalse();
            assertThat(schemaUsage.matcher(texts.getProperty("error.APP_ACCOUNT_NO_ACCESS")).find()).as(bundle).isFalse();
        }
    }

    @Test
    @DisplayName("SET-053 application.properties has the instance name of the bundle in its defaults (the build puts it in) and its source holds no second literal of it")
    void set053_theInstanceNameOfApplicationProperties() throws IOException {
        String name = new InstallDefaults(new PackagedSeed()).instanceName();
        String built;
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            built = StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(built).contains("ledger.secrets.instance=" + name + "\n").doesNotContain("@instance-name@");
        assertThat(Repository.read("ledger-cli/src/main/resources/application.properties").toLowerCase(Locale.ROOT)).doesNotContain(name)
                .contains("@instance-name@");
    }
}
