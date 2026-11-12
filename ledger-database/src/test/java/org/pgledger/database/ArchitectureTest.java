package org.pgledger.database;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
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
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.PrincipleRules;
import org.pgledger.testing.SourceRules;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The ARC rules that apply to the database module (the shared rules come from the domain test fixtures). */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.pgledger.database");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path TEST_JAVA = Path.of("src", "test", "java");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of(
            PrincipleRules.BEAN_OF_OWN_TYPE, Map.of(
                    "LedgerDataSourceConfiguration.registeredDriver",
                    "the driver is made at run time from the jar the user registered, inside a class loader of its own "
                            + "(DriverLoader.load, a factory call): there is no class of ours with a constructor that Spring could "
                            + "call, and the auto-configuration of Spring Boot cannot load a driver from a path"),
            PrincipleRules.OPTIONAL_STORED, Map.of(
                    "LedgerJdbcConfiguration.jdbcMappingContext",
                    "it overrides AbstractJdbcConfiguration.jdbcMappingContext of Spring Data JDBC, whose signature takes an "
                            + "Optional<NamingStrategy>; an override cannot change the type of a parameter"));

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(MAIN, MAIN_JAVA, PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }

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
                // the database module never opens a transaction: the caller (the service) owns it. The one use of
                // @Transactional here is MANDATORY on the class that allocates ids, which only refuses a call without one.
                new Rule("ARC-013 @Transactional here only as propagation MANDATORY, on a class",
                        classes().that().areAnnotatedWith(Transactional.class).should(haveMandatoryPropagation())),
                new Rule("ARC-013 no @Transactional on a method here", noMethods().should().beAnnotatedWith(
                        Transactional.class)),
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

    /** A class whose {@code @Transactional} is {@code propagation = MANDATORY}. */
    private static ArchCondition<JavaClass> haveMandatoryPropagation() {
        return new ArchCondition<>("be @Transactional(propagation = MANDATORY)") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                Propagation propagation = type.getAnnotationOfType(Transactional.class).propagation();
                if (propagation != Propagation.MANDATORY) {
                    events.add(SimpleConditionEvent.violated(type, type.getName() + " is @Transactional with "
                            + propagation));
                }
            }
        };
    }

    /** A class with a code unit that has a catch block for {@code throwable}. */
    private static ArchCondition<JavaClass> notCatch(String throwable) {
        return new ArchCondition<>("not catch " + throwable) {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                for (JavaCodeUnit unit : type.getCodeUnits()) {
                    for (TryCatchBlock block : unit.getTryCatchBlocks()) {
                        for (JavaClass caught : block.getCaughtThrowables()) {
                            if (throwable.equals(caught.getName())) {
                                events.add(SimpleConditionEvent.violated(unit,
                                        unit.getFullName() + " catches " + throwable));
                            }
                        }
                    }
                }
            }
        };
    }

    @Test
    @DisplayName("ARC-036 catch rules: no SQLException catch outside the driver boundary, no empty catch, no broad catch")
    void arc036_catchRules() {
        classes().that().resideOutsideOfPackage("org.pgledger.database.driver..")
                .should(notCatch("java.sql.SQLException")).check(MAIN);
        assertThat(CodingScan.catchProblems(Set.of(), "driver", MAIN_JAVA, TEST_JAVA)).isEmpty();
    }

    @Test
    @DisplayName("ARC-037 string rules: literal first in equals, no == on strings, no string switch, no intern(), no new String(")
    void arc037_stringRules() {
        noClasses().should().callMethod(String.class, "intern")
                .orShould().callConstructor(String.class, String.class)
                .orShould().callConstructor(String.class, byte[].class)
                .orShould().callConstructor(String.class, byte[].class, java.nio.charset.Charset.class)
                .check(MAIN);
        assertThat(CodingScan.stringProblems(Set.of(), MAIN_JAVA, TEST_JAVA)).isEmpty();
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
