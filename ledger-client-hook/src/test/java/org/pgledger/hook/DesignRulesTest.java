package org.pgledger.hook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.hook.keys.SessionKeyException;
import org.pgledger.testing.SourceRules;

/**
 * HK-041 .. HK-048: the structure that the development principles ask of the hook module (unit W6): the host specific
 * tool name shapes live in the three dialects, the author is one value, the key store is split by what changes it, no
 * {@code Optional} is taken apart with {@code get()} or kept in a field or parameter, strings are compared with
 * {@code Objects.equals}, and the constants that are sets are immutable. These are source and class rules because the
 * behaviour they protect (what is signed, what is refused) is covered by the process and in-process tests of HK-001
 * .. HK-040, which must pass unchanged.
 */
class DesignRulesTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final String HOOK = "org.pgledger.hook";
    private static final String KEYS = "org.pgledger.hook.keys";

    private static final JavaClasses hook = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(HOOK);

    private static String source(String relative) {
        try {
            return Files.readString(MAIN_JAVA.resolve(relative));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static long patternFields(String className) {
        return hook.get(className).getFields().stream()
                .filter(field -> field.getRawType().isEquivalentTo(Pattern.class)).count();
    }

    // ---------------------------------------------------------------- HK-041 vendor boundary

    @Test
    @DisplayName("HK-041 McpToolName is the value (server, tool) and the server name check: its only pattern is the server name, and its source names no host and no host spelling")
    void mcpToolNameKnowsNoHost() {
        assertThat(patternFields(HOOK + ".McpToolName")).as("patterns of McpToolName").isEqualTo(1);
        assertThat(source("org/pgledger/hook/McpToolName.java"))
                .doesNotContainIgnoringCase("claude").doesNotContainIgnoringCase("codex")
                .doesNotContainIgnoringCase("antigravity").doesNotContainIgnoringCase("gemini")
                .doesNotContain("call_mcp_tool").doesNotContain("mcp__").doesNotContain("mcp:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ClaudeDialect", "CodexDialect", "AntigravityDialect"})
    @DisplayName("HK-041 each dialect owns the tool name shape(s) of its host: at least one pattern, compiled once as a constant")
    void eachDialectOwnsItsShapes(String dialect) {
        assertThat(patternFields(HOOK + "." + dialect)).isGreaterThanOrEqualTo(1);
        assertThat(hook.get(HOOK + "." + dialect).getFields().stream()
                .filter(field -> field.getRawType().isEquivalentTo(Pattern.class))
                .allMatch(field -> field.getModifiers().contains(JavaModifier.STATIC)
                        && field.getModifiers().contains(JavaModifier.FINAL))).isTrue();
    }

    @Test
    @DisplayName("HK-041 no Pattern.compile outside a static initializer: nothing is compiled per request")
    void noPatternCompilePerRequest() {
        List<String> problems = new ArrayList<>();
        hook.stream().flatMap(type -> type.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTarget().getOwner().isEquivalentTo(Pattern.class)
                        && "compile".equals(call.getTarget().getName()) && !"<clinit>".equals(call.getOrigin().getName()))
                .forEach(call -> problems.add(call.getDescription()));
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("HK-041 the dialect says nothing of the vendor of the author: Client holds the one pairing of a client with its vendor")
    void clientHoldsTheVendor() {
        assertThat(Client.CLAUDE.vendor()).isEqualTo(Vendor.CLAUDE);
        assertThat(Client.CODEX.vendor()).isEqualTo(Vendor.CODEX);
        assertThat(Client.ANTIGRAVITY.vendor()).isEqualTo(Vendor.GEMINI);
        assertThat(Stream.of(Client.values()).map(Client::vendor)).doesNotHaveDuplicates();
        assertThat(hook.get(HOOK + ".HostDialect").getMethods().stream().map(method -> method.getName()))
                .doesNotContain("vendor");
    }

    // ---------------------------------------------------------------- HK-042 author

    @Test
    @DisplayName("HK-042 the author format is the one value Author: the vendor of the format, the owner rule and the session id check are in no other source")
    void authorFormatIsInOnePlace() {
        assertThat(source("org/pgledger/hook/Author.java")).contains("record Author");
        assertThat(MAIN_JAVA.resolve("org/pgledger/hook/AuthorName.java")).doesNotExist();
        assertThat(hook.get(HOOK + ".Intake$Attest").getFields().stream().map(field -> field.getName()))
                .as("the host id of an attest call is a HostIdentity, not a raw String")
                .contains("host").doesNotContain("hostId");
        assertThat(hook.get(HOOK + ".Intake$Attest").getField("host").getRawType().getName())
                .isEqualTo(HOOK + ".HostIdentity");
    }

    @Test
    @DisplayName("HK-042 the string 'attest' is gone from Vendor: the author is built by Author.of(vendor, host id)")
    void vendorBuildsNoAuthorText() {
        assertThat(hook.get(HOOK + ".Vendor").getMethods().stream().map(method -> method.getName()))
                .doesNotContain("attest");
    }

    // ---------------------------------------------------------------- HK-044 key store split

    @Test
    @DisplayName("HK-044 SessionKeyStore opens the folder and orders the steps only: no key encoding, no key algorithm, no file attribute view, no channel")
    void keyStoreOnlyOrchestrates() {
        noClasses().that().haveFullyQualifiedName(KEYS + ".SessionKeyStore").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Base64")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.KeyFactory")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.KeyPairGenerator")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.Signature")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.MessageDigest")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.charset.CharsetDecoder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.AclFileAttributeView")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.AclEntry")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.PosixFilePermissions")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.channels.SeekableByteChannel")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.StandardOpenOption")
                .check(hook);
    }

    @Test
    @DisplayName("HK-044 the file format (Base64, the key algorithm) is used by SessionKeyFile alone")
    void formatOnlyInSessionKeyFile() {
        noClasses().that().resideInAPackage(KEYS).and().doNotHaveFullyQualifiedName(KEYS + ".SessionKeyFile")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Base64")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.KeyFactory")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.KeyPairGenerator")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.Signature")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.charset.CharsetDecoder")
                .check(hook);
    }

    @Test
    @DisplayName("HK-044 the protection (POSIX modes, ACL entries, owner) is Protection alone; the channels are KeyFileReader and KeyFileWriter alone")
    void protectionAndChannelsAreSeparate() {
        noClasses().that().resideInAPackage(KEYS).and().haveNameNotMatching(".*\\.Protection(\\$.*)?")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.AclFileAttributeView")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.AclEntry")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.PosixFilePermission")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.PosixFilePermissions")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.UserPrincipal")
                .check(hook);
        noClasses().that().resideInAPackage(KEYS).and().doNotHaveFullyQualifiedName(KEYS + ".KeyFileWriter")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.FileAlreadyExistsException")
                .check(hook);
        noClasses().that().resideInAPackage(KEYS).and().doNotHaveFullyQualifiedName(KEYS + ".KeyFileReader")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.attribute.BasicFileAttributes")
                .check(hook);
    }

    @Test
    @DisplayName("HK-044 the refusal reasons of the key store are exactly the eleven of the contract, in this order")
    void reasonsAreKept() {
        assertThat(Stream.of(SessionKeyException.Reason.values()).map(Enum::name).toList()).containsExactly(
                "AUTHOR_INVALID", "FOLDER_UNAVAILABLE", "TOO_OPEN", "NOT_A_REGULAR_FILE", "CORRUPT", "AUTHOR_MISMATCH",
                "SERVER_MISMATCH", "WRONG_OWNER", "OWNER_UNKNOWN", "KEY_MISMATCH", "IO_FAILED");
    }

    @Test
    @DisplayName("HK-044 the key file is still opened with NOFOLLOW_LINKS and created with CREATE_NEW, each in its own class")
    void openAndCreateKeepTheirFlags() {
        assertThat(source("org/pgledger/hook/keys/KeyFileReader.java")).contains("NOFOLLOW_LINKS").doesNotContain("CREATE_NEW");
        assertThat(source("org/pgledger/hook/keys/KeyFileWriter.java")).contains("CREATE_NEW").doesNotContain("NOFOLLOW_LINKS");
    }

    // ---------------------------------------------------------------- HK-045 Optional

    @Test
    @DisplayName("HK-045 no Optional.get(), isPresent() or isEmpty() in the hook: map, orElseGet, orElseThrow instead")
    void noOptionalTakenApart() {
        noClasses().should().callMethod(Optional.class, "get")
                .orShould().callMethod(Optional.class, "isPresent")
                .orShould().callMethod(Optional.class, "isEmpty")
                .check(hook);
    }

    // HK-045: no Optional field, record component or parameter, and no orElse(null), are the shared rules ARC-PRN-02 and
    // ARC-PRN-04 of ArchitectureTest (one place per rule, TEST_CASES.md section 25).

    // ---------------------------------------------------------------- HK-046 strings

    /** A receiver that is a variable or a call, not a literal and not a constant: {@code name.equals(} and {@code name().equals(}. */
    private static final Pattern VARIABLE_EQUALS = Pattern.compile("(?<![\\w\"])[a-z]\\w*(\\(\\))?\\.equals\\(");

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    @DisplayName("HK-046 no variable.equals(...) in the hook: a literal or constant first, Objects.equals for two variables")
    void noVariableEquals() {
        List<String> problems = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(MAIN_JAVA)) {
            int number = 0;
            for (String line : read(file).split("\n", -1)) {
                number++;
                String code = line.strip();
                boolean comment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*");
                if (!comment && VARIABLE_EQUALS.matcher(line).find()) {
                    problems.add(file + ":" + number + ": " + code);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    // ---------------------------------------------------------------- HK-047 constants

    @Test
    @DisplayName("HK-047 a static Set constant is built with Set.of, Set.copyOf or an unmodifiable view: none is a mutable HashSet or EnumSet")
    void staticSetsAreImmutable() {
        Pattern declaration = Pattern.compile("static\\s+final\\s+Set<[^;]*;", Pattern.DOTALL);
        List<String> problems = new ArrayList<>();
        for (Path file : SourceRules.javaFiles(MAIN_JAVA)) {
            var matcher = declaration.matcher(read(file));
            while (matcher.find()) {
                String text = matcher.group();
                if (!(text.contains("Set.of(") || text.contains("Set.copyOf(") || text.contains("unmodifiable"))) {
                    problems.add(file + ": " + text.replaceAll("\\s+", " "));
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
