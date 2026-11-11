package org.pgledger.database;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.SourceRules;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The ARC rules that apply to the database module (the shared rules come from the domain test fixtures). */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.pgledger.database");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** One rule of the catalogue under its ID. */
    private record Rule(String id, ArchRule rule) {
        @Override
        public String toString() {
            return id;
        }
    }

    static Stream<Rule> rules() {
        return Stream.of(
                new Rule("ARC-001 no Lombok", ArchitectureRules.noLombok()),
                new Rule("ARC-002 no setAccessible", ArchitectureRules.noSetAccessible()),
                new Rule("ARC-003 reflection only in the driver boundary",
                        ArchitectureRules.reflectionOnlyIn("org.pgledger.database.driver..")),
                new Rule("ARC-004 no process execution", ArchitectureRules.noProcessExecution()),
                new Rule("ARC-006 no standard streams, SLF4J only", ArchitectureRules.noStandardStreamsAndOtherLoggers()),
                new Rule("ARC-007 no getenv, no @Value", ArchitectureRules.noEnvironmentAccessAndValueAnnotation()),
                new Rule("ARC-010 no JSON tree models", ArchitectureRules.noJsonTreeModels()),
                new Rule("ARC-018 no ThreadLocal", ArchitectureRules.noThreadLocalAndNoMutableStatics()),
                new Rule("ARC-018 static fields are final", ArchitectureRules.staticFieldsAreFinal()),
                new Rule("ARC-019/024 no ambient time", ArchitectureRules.noAmbientTime()),
                new Rule("ARC-009 no web in the database module", noClasses().should().dependOnClassesThat()
                        .resideInAnyPackage("org.springframework.web..", "org.springframework.http..",
                                "jakarta.servlet..")),
                new Rule("ARC-012 JDBC clients only in the repository package", noClasses().that()
                        .resideOutsideOfPackage("org.pgledger.database.repository..").should().dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.JdbcTemplate")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.JdbcOperations")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")),
                new Rule("ARC-013 no @Transactional here", noClasses().should().beAnnotatedWith(
                        "org.springframework.transaction.annotation.Transactional")),
                new Rule("ARC-027 DriverManager, class loaders and the pool only in the driver package",
                        noClasses().that().resideOutsideOfPackage("org.pgledger.database.driver..").should()
                                .dependOnClassesThat().haveFullyQualifiedName("java.sql.DriverManager")
                                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URLClassLoader")
                                .orShould().dependOnClassesThat().resideInAPackage("com.zaxxer.hikari..")
                                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                        "org.springframework.jdbc.datasource.SimpleDriverDataSource")),
                new Rule("ARC-027 DriverManager nowhere", noClasses().should().dependOnClassesThat()
                        .haveFullyQualifiedName("java.sql.DriverManager")),
                new Rule("ARC-029 no JPA", noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate..", "org.springframework.data.jpa..")));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void theRuleHolds(Rule rule) {
        rule.rule().check(MAIN);
    }

    @Test
    @DisplayName("ARC-011: the history repository can insert and read, nothing else")
    void historyIsInsertAndReadOnly() {
        assertThat(Stream.of(HistoryRepository.class.getMethods()).map(m -> m.getName()).collect(
                java.util.stream.Collectors.toSet())).containsExactlyInAnyOrder("insert", "findById", "findByIdIn",
                "findByEntityAndEntityIdOrderById", "findByRequestIdOrderById");
        assertThat(HistoryRepository.class.getInterfaces()).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("Repository", "Insertable");
    }

    @Test
    @DisplayName("ARC-017: configuration properties are records")
    void configurationPropertiesAreRecords() {
        classes().that().areAnnotatedWith(ConfigurationProperties.class).should().beRecords().check(MAIN);
    }

    @Test
    @DisplayName("ARC-008/025/032 and the text part of ARC-002: sources")
    void sourceRules() {
        assertThat(SourceRules.swallowedOrBroadCatches(MAIN_JAVA, Set.of())).isEmpty();
        assertThat(SourceRules.utilityNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.vendorNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.operatingSystemDetection(MAIN_JAVA, Path.of("src", "test", "java"))).isEmpty();
        ArchitectureRules.noOperatingSystemDetection().check(MAIN);
        assertThat(SourceRules.forbiddenBuildSwitches(List.of(Path.of("build.gradle.kts")))).isEmpty();
    }
}
