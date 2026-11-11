package org.pgledger.attestation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.SourceRules;

/** ATT-020: the attestation module is plain JDK code that the hook and the server share. */
class ArchitectureTest {

    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.pgledger.attestation");

    @Test
    @DisplayName("ATT-020 the module uses no network, file, process, reflection, JDBC, Spring, Jackson or other module of the product")
    void onlyTheJdkCore() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "javax.net..", "java.nio.file..", "java.nio.channels..", "java.sql..", "javax.sql..",
                        "java.lang.reflect..", "java.lang.foreign..", "org.springframework..", "tools.jackson..",
                        "com.fasterxml..", "org.pgledger.domain..", "org.pgledger.database..",
                        "org.pgledger.application..", "org.pgledger.hook..", "org.pgledger.mcp..", "lombok..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
                .check(classes);
    }

    @Test
    @DisplayName("ATT-020 no mutable static state")
    void noMutableStatics() {
        fields().that().areStatic().should().beFinal().check(classes);
    }

    @Test
    @DisplayName("ARC-033 no operating system detection in main and test code")
    void noOperatingSystemDetection() {
        ArchitectureRules.noOperatingSystemDetection().check(classes);
        assertThat(SourceRules.operatingSystemDetection(Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch")
    void catchRules() {
        assertThat(CodingScan.catchProblems(Set.of(), null, Path.of("src", "main", "java"),
                Path.of("src", "test", "java"))).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String(")
    void stringRules() {
        assertThat(CodingScan.stringProblems(Set.of(), Path.of("src", "main", "java"), Path.of("src", "test", "java")))
                .isEmpty();
    }
}
