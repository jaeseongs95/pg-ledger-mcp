package org.pgledger.hook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.nio.file.Path;
import java.util.Set;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.SourceRules;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HK-018 (code side): no network, process, reflection or framework access in any hook class; file system access only in
 * the {@code keys} package; the process (System) only in Main.
 */
class ArchitectureTest {

    private static final String KEYS_PACKAGE = "org.pgledger.hook.keys..";

    private static final JavaClasses hook = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.pgledger.hook");

    @Test
    @DisplayName("HK-018 no hook class uses a network, process, reflection, foreign-memory, JDBC, Spring or Lombok API")
    void noForbiddenApi() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "javax.net..", "java.sql..", "javax.sql..",
                        "java.lang.reflect..", "java.lang.foreign..", "org.springframework..", "lombok..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                .check(hook);
    }

    @Test
    @DisplayName("HK-018 file system APIs (java.nio.file, java.nio.channels, java.io.File*) are used only by the classes of the keys package")
    void fileSystemOnlyInKeysPackage() {
        noClasses().that().resideOutsideOfPackage(KEYS_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..", "java.nio.channels..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileInputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileOutputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileReader")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileWriter")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.RandomAccessFile")
                .check(hook);
        // the package exists in the imported classes: the rule above is not vacuous for it
        assertThat(hook.stream().anyMatch(type -> "org.pgledger.hook.keys".equals(type.getPackageName()))).isTrue();
    }

    @Test
    @DisplayName("HK-037 the key file is opened once without following links and read from that channel: no read of a path (readAllBytes, readString, readAllLines, newInputStream, newBufferedReader)")
    void keyFileIsNeverReadByPath() {
        noClasses().should().callMethod(java.nio.file.Files.class, "readAllBytes", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "readString", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "readAllLines", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "newInputStream", java.nio.file.Path.class,
                        java.nio.file.OpenOption[].class)
                .orShould().callMethod(java.nio.file.Files.class, "newBufferedReader", java.nio.file.Path.class)
                .check(hook);
    }

    @Test
    @DisplayName("HK-018 only Main touches java.lang.System, and only for stdin, stdout, stderr, exit, System.getenv(String) and System.getProperty(String)")
    void systemOnlyInMain() {
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hook.Main")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
                .check(hook);
        // Main reads one named environment variable and one named property, never the whole environment or all properties
        noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getProperty", String.class, String.class)
                .orShould().callMethod(System.class, "getProperties")
                .check(hook);
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hook.Main")
                .should().callMethod(System.class, "getenv", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class)
                .check(hook);
    }

    @Test
    @DisplayName("ARC-033 no operating system detection in main and test code")
    void noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(hook);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of(), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String( in main")
    void arc037_stringRules() {
        noClasses().should().callMethod(String.class, "intern")
                .orShould().callConstructor(String.class, String.class)
                .orShould().callConstructor(String.class, byte[].class)
                .orShould().callConstructor(String.class, byte[].class, java.nio.charset.Charset.class)
                .check(hook);
        // Run.java turns captured process bytes into text for an assertion: no file or stream API applies to a byte[]
        assertThat(CodingScan.stringProblems(Set.of("Run.java"), Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("HK-018 no mutable static state")
    void noMutableStatics() {
        fields().that().areStatic().should().beFinal().check(hook);
    }
}
