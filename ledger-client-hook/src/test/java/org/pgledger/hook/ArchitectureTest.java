package org.pgledger.hook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HK-018 (code side): no network, file, environment, process, reflection or framework access in the hook classes. */
class ArchitectureTest {

    private static JavaClasses hook;

    @BeforeAll
    static void importClasses() {
        hook = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("org.pgledger.hook");
    }

    @Test
    @DisplayName("HK-018 the hook classes use no network, file system, process, reflection, JDBC, Spring or Lombok API")
    void noForbiddenApi() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "javax.net..", "java.nio.file..", "java.nio.channels..", "java.sql..", "javax.sql..",
                        "java.lang.reflect..", "java.lang.foreign..", "org.springframework..", "lombok..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileInputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileOutputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileReader")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileWriter")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.RandomAccessFile")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                .check(hook);
    }

    @Test
    @DisplayName("HK-018 only Main touches java.lang.System (stdin, stdout, stderr, exit); nothing reads the environment or properties")
    void systemOnlyInMain() {
        noClasses().that().doNotHaveFullyQualifiedName("org.pgledger.hook.Main")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.System")
                .check(hook);
        noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class)
                .orShould().callMethod(System.class, "getProperty", String.class, String.class)
                .orShould().callMethod(System.class, "getProperties")
                .check(hook);
    }

    @Test
    @DisplayName("HK-018 no mutable static state")
    void noMutableStatics() {
        fields().that().areStatic().should().beFinal().check(hook);
    }
}
