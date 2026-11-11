package org.pgledger.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.history.HistoryWriter;
import org.pgledger.database.repository.HistoryRepository;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.SourceRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The ARC rules that apply to the application module (the shared rules come from the domain test fixtures). */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.pgledger.application");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path TEST_JAVA = Path.of("src", "test", "java");

    /** One rule of the catalogue under its ID. */
    private record Rule(String id, ArchRule rule) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static final DescribedPredicate<JavaMethodCall> CALLS_STRING_HASH_CODE = DescribedPredicate.describe(
            "calls String.hashCode (the first step of a switch over a String)",
            call -> "hashCode".equals(call.getTarget().getName()) && call.getTargetOwner().isEquivalentTo(String.class));

    private static final DescribedPredicate<JavaMethodCall> INSERTS_HISTORY = DescribedPredicate.describe(
            "inserts into the history", call -> "insert".equals(call.getTarget().getName())
                    && call.getTargetOwner().isAssignableTo(HistoryRepository.class));

    static Stream<Rule> rules() {
        return Stream.of(
                new Rule("ARC-001 no Lombok", ArchitectureRules.noLombok()),
                new Rule("ARC-002 no setAccessible", ArchitectureRules.noSetAccessible()),
                new Rule("ARC-003 no reflection", ArchitectureRules.reflectionOnlyIn("org.pgledger.nowhere..")),
                new Rule("ARC-004 no process execution", ArchitectureRules.noProcessExecution()),
                new Rule("ARC-006 no standard streams, SLF4J only", ArchitectureRules.noStandardStreamsAndOtherLoggers()),
                new Rule("ARC-007 no getenv, no @Value", ArchitectureRules.noEnvironmentAccessAndValueAnnotation()),
                new Rule("ARC-010 no JSON tree models", ArchitectureRules.noJsonTreeModels()),
                new Rule("ARC-018 no ThreadLocal", ArchitectureRules.noThreadLocalAndNoMutableStatics()),
                new Rule("ARC-018 static fields are final", ArchitectureRules.staticFieldsAreFinal()),
                new Rule("ARC-019/024 no ambient time", ArchitectureRules.noAmbientTime()),
                new Rule("ARC-009 no web in the application module", noClasses().should().dependOnClassesThat()
                        .resideInAnyPackage("org.springframework.web..", "org.springframework.http..",
                                "jakarta.servlet..")),
                new Rule("ARC-009 the application does not reach into schema or driver code of the database module",
                        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                                "org.pgledger.database.schema..", "org.pgledger.database.driver..")),
                new Rule("ARC-012 JDBC clients only in the repository package", noClasses().should()
                        .dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.JdbcTemplate")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")),
                new Rule("ARC-013 no TransactionTemplate", noClasses().should().dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.transaction.support.TransactionTemplate")),
                new Rule("ARC-013 @Transactional only on services", noClasses().that().areNotAnnotatedWith(
                        Service.class).should().beAnnotatedWith(Transactional.class)),
                new Rule("ARC-015 no switch over a String", noClasses().should().callMethodWhere(
                        CALLS_STRING_HASH_CODE)),
                new Rule("ARC-011 only the history writer inserts history rows", noClasses().that()
                        .doNotHaveFullyQualifiedName(HistoryWriter.class.getName()).should()
                        .callMethodWhere(INSERTS_HISTORY)),
                new Rule("ARC-029 no JPA", noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate.orm..", "org.springframework.data.jpa..")));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void theRuleHolds(Rule rule) {
        rule.rule().check(MAIN);
    }

    @Test
    @DisplayName("ARC-013: every service declares its transaction once, at class level")
    void servicesAreTransactionalOnTheClass() {
        classes().that().areAnnotatedWith(Service.class).should().beAnnotatedWith(Transactional.class).check(MAIN);
    }

    @Test
    @DisplayName("ARC-036 catch rules: no empty catch, no broad catch")
    void arc036_catchRules() {
        assertThat(CodingScan.catchProblems(Set.of(), null, MAIN_JAVA, TEST_JAVA)).isEmpty();
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
        assertThat(SourceRules.forbiddenBuildSwitches(List.of(Path.of("build.gradle.kts")))).isEmpty();
    }

    @Test
    @DisplayName("ARC-038: every owner operation of the services names its actor; no main code has an actor-less path or "
            + "the system actor")
    void arc038_ownerOperationsNameTheirActor() throws java.io.IOException {
        Set<String> operations = Set.of("grant", "revoke", "set", "register", "registerReplacing");
        for (Class<?> service : List.of(org.pgledger.application.admin.GrantService.class,
                org.pgledger.application.admin.CommonCodeService.class,
                org.pgledger.application.admin.SessionKeyService.class)) {
            for (java.lang.reflect.Method method : service.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers()) && operations.contains(method.getName())) {
                    assertThat(method.getParameterTypes()).as(method.toString())
                            .contains(org.pgledger.domain.value.OwnerIdentity.class);
                }
            }
        }
        try (Stream<Path> files = java.nio.file.Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertThat(java.nio.file.Files.readString(file)).as(file.toString()).doesNotContain("OwnerIdentity.SYSTEM")
                        .doesNotContain("OwnerIdentity.of(\"system\")");
            }
        }
    }
}
