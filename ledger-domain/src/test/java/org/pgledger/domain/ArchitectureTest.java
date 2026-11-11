package org.pgledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.SourceRules;

/** ARC rules that apply to the domain module, DOM-035, and negative controls proving the rules bite. */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.pgledger.domain");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    @Test
    void arc001_002_003_004_noLombokNoSetAccessibleNoReflectionNoProcesses() {
        ArchitectureRules.noLombok().check(MAIN);
        ArchitectureRules.noSetAccessible().check(MAIN);
        ArchitectureRules.reflectionOnlyIn().check(MAIN);
        ArchitectureRules.noProcessExecution().check(MAIN);
        assertThat(SourceRules.forbiddenBuildSwitches(List.of(Path.of("build.gradle.kts")))).isEmpty();
    }

    @Test
    void arc006_007_noStreamsNoEnvironmentNoValue() {
        ArchitectureRules.noStandardStreamsAndOtherLoggers().check(MAIN);
        ArchitectureRules.noEnvironmentAccessAndValueAnnotation().check(MAIN);
    }

    @Test
    void arc008_noSwallowedExceptions() {
        assertThat(SourceRules.swallowedOrBroadCatches(MAIN_JAVA, Set.of())).isEmpty();
    }

    @Test
    void arc009_domainIsIndependent() {
        ArchitectureRules.domainIsIndependent().check(MAIN);
    }

    @Test
    void arc010_018_019_024_noTreeModelsNoThreadLocalNoAmbientTime() {
        ArchitectureRules.noJsonTreeModels().check(MAIN);
        ArchitectureRules.noThreadLocalAndNoMutableStatics().check(MAIN);
        ArchitectureRules.staticFieldsAreFinal().check(MAIN);
        ArchitectureRules.noAmbientTime().check(MAIN);
    }

    @Test
    void arc022_dom035_aggregatesAreRecordsWithoutBooleansOrLocalTime() {
        ArchitectureRules.modelTypesAreRecordsEnumsOrInterfaces().check(MAIN);
        ArchitectureRules.aggregatesHaveNoBooleanNorLocalTime().check(MAIN);
    }

    @Test
    void arc025_032_noUtilityNamesNoVendorNames() {
        assertThat(SourceRules.utilityNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.vendorNames(MAIN_JAVA)).isEmpty();
    }

    @Test
    void negativeControl_theRulesDetectBadCode() {
        JavaClasses bad = new ClassFileImporter().importPackages("org.pgledger.archfixture");
        assertThat(bad.contain("org.pgledger.archfixture.BadCode")).isTrue();
        List<ArchRule> rules = List.of(ArchitectureRules.noSetAccessible(), ArchitectureRules.reflectionOnlyIn(),
                ArchitectureRules.noProcessExecution(), ArchitectureRules.noStandardStreamsAndOtherLoggers(),
                ArchitectureRules.noEnvironmentAccessAndValueAnnotation(), ArchitectureRules.noAmbientTime(),
                ArchitectureRules.noThreadLocalAndNoMutableStatics(), ArchitectureRules.staticFieldsAreFinal());
        for (ArchRule rule : rules) {
            assertThat(rule.evaluate(bad).hasViolation()).as(rule.getDescription()).isTrue();
        }
        // the time rule flags ambient now(), the default zones, Date and currentTimeMillis, but not now(clock)
        String report = String.join("\n", ArchitectureRules.noAmbientTime().evaluate(bad).getFailureReport()
                .getDetails());
        assertThat(report).contains("ambientNow", "ambientLocal", "ambientOffset", "defaultZone", "defaultTimeZone",
                "legacyDate", "millis").doesNotContain("goodNow");
    }
}
