package org.pgledger.cli;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.secret.ConsoleSecretInput;
import org.pgledger.cli.secret.SecretInput;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.SourceRules;
import org.springframework.beans.factory.annotation.Value;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class ArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.pgledger.cli");

    @Test
    @DisplayName("SEC-001 permissions are given at creation: no code widens a file and narrows it later")
    void sec001_noPermissionChangeAfterCreation() {
        noClasses().should().callMethod(Files.class, "setPosixFilePermissions", Path.class, Set.class)
                .orShould().callMethod(Files.class, "setAttribute", Path.class, String.class, Object.class, LinkOption[].class)
                .orShould().callMethod(java.io.File.class, "setReadable", boolean.class, boolean.class)
                .orShould().callMethod(java.io.File.class, "setWritable", boolean.class, boolean.class)
                .orShould().callMethod(java.io.File.class, "setExecutable", boolean.class, boolean.class)
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-002 replacement is temp file plus move: no copy-over and no whole-file write helpers")
    void sec002_noInPlaceReplacement() {
        noClasses().should().callMethod(Files.class, "copy", Path.class, Path.class, java.nio.file.CopyOption[].class)
                .orShould().callMethod(Files.class, "write", Path.class, byte[].class, java.nio.file.OpenOption[].class)
                .orShould().callMethod(Files.class, "writeString", Path.class, CharSequence.class, java.nio.file.OpenOption[].class)
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-003 no code reads the environment, starts a process or reads a value from standard input")
    void sec003_noEnvironmentProcessOrStdin() {
        noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().accessField(System.class, "in")
                .check(MAIN);
    }

    @Test
    @DisplayName("SEC-003 Console input is the only SecretInput implementation")
    void sec003_consoleIsTheOnlyInput() {
        classes().that().implement(SecretInput.class).should().haveFullyQualifiedName(ConsoleSecretInput.class.getName()).check(MAIN);
    }

    @Test
    @DisplayName("Console output goes through one place only: no System.out/err or printStackTrace elsewhere")
    void consoleOutputHasOneSink() {
        noClasses().that().areNotAssignableTo(CliOutputConfiguration.class).should(ACCESS_STANDARD_STREAMS).check(MAIN);
    }

    @Test
    @DisplayName("ARC-033 no operating system detection in main and test code")
    void noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(MAIN);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch outside the test entry that plays the CLI boundary")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of("CliExtension.java"), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String(")
    void arc037_stringRules() {
        noClasses().should().callMethod(String.class, "intern")
                .orShould().callConstructor(String.class, String.class)
                .orShould().callConstructor(String.class, byte[].class)
                .orShould().callConstructor(String.class, byte[].class, java.nio.charset.Charset.class)
                .check(MAIN);
        assertThat(CodingScan.stringProblems(Set.of(), Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("The module does not depend on JDBC or a DataSource")
    void noJdbcDependency() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..")
                .check(MAIN);
    }

    @Test
    @DisplayName("Configuration comes from ConfigurationProperties records, never @Value")
    void noValueAnnotation() {
        noFields().should().beAnnotatedWith(Value.class).check(MAIN);
        noClasses().should().dependOnClassesThat(belongToAnyOf(Value.class)).check(MAIN);
    }
}
