package org.pgledger.admin;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.testing.ArchitectureRules;
import org.pgledger.testing.CodingScan;
import org.pgledger.testing.PrincipleRules;
import org.pgledger.testing.SourceRules;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The ARC rules that apply to the admin web (the shared rules come from the domain test fixtures) and the rules of the
 * admin itself: no ledger workflow service, no repository in a controller, one error advice, filters registered (not
 * components) and never writing a body, no custom Sec-Fetch-Site or Origin check, secrets compared in constant time.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN = ArchitectureRules.productionClasses("org.pgledger.admin");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    /** ARC-PRN-01 .. 06 (TEST_CASES.md section 25): what this module allows of each rule (key to reason). */
    private static final Map<String, Map<String, String>> PRINCIPLE_ALLOWED = Map.of(
            PrincipleRules.BEAN_OF_OWN_TYPE, Map.of(
                    "AdminBeansConfiguration.adminMode",
                    "the console exists exactly when the ledger database does: the value is decided from which beans exist (the "
                            + "DataSource of the registered driver), which only a method that is given them can ask",
                    "SetupConfiguration.driverRegistrationFileLocation",
                    "the value comes from a setting of this application (ledger.admin.driver-file); the record is a class of the "
                            + "database module, which serves two programs and cannot know the setting of either",
                    "SetupConfiguration.driverRegistrar",
                    "the registrar is a class of the database module that serves two programs (the admin web and the command line "
                            + "tool) and takes what differs between them as arguments: the JDBC URL and the one directory a jar may "
                            + "be in, a setting of this application; the module cannot make it a component"));

    static Stream<PrincipleRules.Rule> principleRules() {
        return PrincipleRules.all(MAIN, MAIN_JAVA, PRINCIPLE_ALLOWED).stream();
    }

    @ParameterizedTest
    @MethodSource("principleRules")
    void thePrincipleRuleHolds(PrincipleRules.Rule rule) {
        assertThat(rule.problems()).isEmpty();
    }
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

    private static final DescribedPredicate<JavaMethodCall> COMPARES_TEXT_OR_BYTES = DescribedPredicate.describe(
            "compares a text or bytes without MessageDigest.isEqual (String.equals, equalsIgnoreCase, contentEquals, "
                    + "Arrays.equals, Objects.equals)",
            call -> (call.getTargetOwner().isEquivalentTo(String.class)
                    && Set.of("equals", "equalsIgnoreCase", "contentEquals", "compareTo")
                            .contains(call.getTarget().getName()))
                    || (call.getTargetOwner().isEquivalentTo(java.util.Arrays.class)
                            && "equals".equals(call.getTarget().getName()))
                    || (call.getTargetOwner().isEquivalentTo(java.util.Objects.class)
                            && "equals".equals(call.getTarget().getName())));

    private static final DescribedPredicate<JavaMethodCall> WRITES_A_BODY = DescribedPredicate.describe(
            "writes a response body (getWriter, getOutputStream)",
            call -> call.getTargetOwner().isAssignableTo(HttpServletResponse.class)
                    && Set.of("getWriter", "getOutputStream").contains(call.getTarget().getName()));

    static Stream<Rule> rules() {
        return Stream.of(
                new Rule("ARC-001 no Lombok", ArchitectureRules.noLombok()),
                new Rule("ARC-002 no setAccessible", ArchitectureRules.noSetAccessible()),
                new Rule("ARC-003 no reflection in the admin", ArchitectureRules.reflectionOnlyIn("org.pgledger.admin.none..")),
                new Rule("ARC-004/ADM-033 no process execution (no runuser, systemctl, psql)",
                        ArchitectureRules.noProcessExecution()),
                new Rule("ARC-006 no standard streams, SLF4J only", ArchitectureRules.noStandardStreamsAndOtherLoggers()),
                new Rule("ARC-007 no getenv, no @Value", ArchitectureRules.noEnvironmentAccessAndValueAnnotation()),
                new Rule("ARC-010 no JSON tree models", ArchitectureRules.noJsonTreeModels()),
                new Rule("ARC-018 no ThreadLocal", ArchitectureRules.noThreadLocalAndNoMutableStatics()),
                new Rule("ARC-018 static fields are final", ArchitectureRules.staticFieldsAreFinal()),
                new Rule("ARC-019/024 no ambient time", ArchitectureRules.noAmbientTime()),
                new Rule("ARC-033 no operating system detection", ArchitectureRules.noOperatingSystemDetection()),
                new Rule("ARC-015 no switch over a String", noClasses().should().callMethodWhere(CALLS_STRING_HASH_CODE)),
                new Rule("ARC-012 JDBC clients are not used by the admin", noClasses().should()
                        .dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.JdbcTemplate")
                        .orShould().dependOnClassesThat().haveFullyQualifiedName(
                                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")),
                new Rule("ARC-033 the admin does not depend on the ledger write workflow services",
                        noClasses().that().resideInAPackage("org.pgledger.admin..").should().dependOnClassesThat()
                                .resideInAPackage("org.pgledger.application.write..")),
                new Rule("the controllers call services only: no class of the console depends on a repository",
                        noClasses().that().resideInAPackage("org.pgledger.admin.console..").should().dependOnClassesThat()
                                .haveSimpleNameEndingWith("Repository")),
                new Rule("ARC-020 the security package compares nothing with String.equals or Arrays.equals",
                        noClasses().that().resideInAPackage("org.pgledger.admin.security..")
                                .should().callMethodWhere(COMPARES_TEXT_OR_BYTES)),
                new Rule("ARC-020 the admin token is compared with MessageDigest.isEqual",
                        classes().that().haveSimpleName("AdminTokenProvider").should().callMethod(MessageDigest.class,
                                "isEqual", byte[].class, byte[].class)),
                new Rule("ARC-029 no JPA", noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate.orm..", "org.springframework.data.jpa..")),
                new Rule("3.8 exactly one error advice, and it extends ResponseEntityExceptionHandler",
                        classes().that().areAnnotatedWith(RestControllerAdvice.class).should()
                                .beAssignableTo(ResponseEntityExceptionHandler.class)),
                new Rule("3.8 no exception handler outside the advice",
                        methods().that().areAnnotatedWith(ExceptionHandler.class).should().beDeclaredIn(
                                org.pgledger.admin.web.AdminHttpExceptionAdvice.class)),
                new Rule("2.1 a filter is a component, and its registration bean holds only the URL pattern and the order",
                        classes().that().areAssignableTo(Filter.class).should().beAnnotatedWith(Component.class)),
                new Rule("plan 3 the owner identity of a controller comes from the argument resolver: no controller "
                        + "takes the Authentication to build it",
                        noClasses().that().resideInAnyPackage("org.pgledger.admin.console..", "org.pgledger.admin.setup..")
                                .and().areAnnotatedWith(org.springframework.stereotype.Controller.class).should()
                                .dependOnClassesThat().haveFullyQualifiedName(
                                        "org.springframework.security.core.Authentication")),
                new Rule("3.8 the filters and the security handlers write no response body",
                        noClasses().that().resideInAnyPackage("org.pgledger.admin.security..", "org.pgledger.admin.web..")
                                .should().callMethodWhere(WRITES_A_BODY)),
                new Rule("ADM-014 no custom Sec-Fetch-Site or Origin check exists (Spring Security CSRF decides)",
                        noClasses().should().haveSimpleNameContaining("SecFetch").orShould().haveSimpleNameContaining("Origin")
                                .orShould().haveSimpleNameContaining("Csrf")));
    }

    @ParameterizedTest
    @MethodSource("rules")
    @DisplayName("ARC: the rule holds for the admin web")
    void theRuleHolds(Rule rule) {
        rule.rule().check(MAIN);
    }

    @Test
    @DisplayName("ARC-008/025/032 and the text part of ARC-002: sources of the admin and of its tests")
    void sourceRules() {
        assertThat(SourceRules.swallowedOrBroadCatches(MAIN_JAVA, Set.of())).isEmpty();
        assertThat(SourceRules.swallowedOrBroadCatches(TEST_JAVA, Set.of())).isEmpty();
        assertThat(SourceRules.utilityNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.vendorNames(MAIN_JAVA)).isEmpty();
        assertThat(SourceRules.forbiddenBuildSwitches(List.of(Path.of("build.gradle.kts")))).isEmpty();
    }

    @Test
    @DisplayName("ADM-068: LoginAttempts cites the Spring Security events documentation as the reason it is self-built, and "
            + "no longer says it is as remembered or unverified")
    void adm068_lockoutCitation() throws IOException {
        String source = Files.readString(MAIN_JAVA.resolve(Path.of("org", "pgledger", "admin", "security",
                "LoginAttempts.java")));

        assertThat(source).contains("https://docs.spring.io/spring-security/reference/servlet/authentication/events.html")
                .doesNotContain("as remembered").doesNotContain("unverified");
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
    @DisplayName("ARC-033: neither the sources nor the tests detect the operating system")
    void noOperatingSystemDetectionInSources() {
        assertThat(SourceRules.operatingSystemDetection(MAIN_JAVA, TEST_JAVA)).isEmpty();
    }

    @Test
    @DisplayName("ADM-014: no source or resource of the admin names Sec-Fetch-Site or an Origin check of its own")
    void noSecFetchSiteInTheSources() throws IOException {
        for (Path root : List.of(Path.of("src", "main"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).as(file.toString()).doesNotContainIgnoringCase("sec-fetch-site")
                            .doesNotContain("getHeader(\"Origin\")");
                }
            }
        }
    }

    @Test
    @DisplayName("3.8: the pages load no script or style that is not a file of the application (no inline script in a template)")
    void noInlineScriptInTemplates() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "templates"))) {
            for (Path file : files.toList()) {
                String html = Files.readString(file);
                assertThat(html).as(file.toString()).doesNotContainPattern("<script(?![^>]*\\bsrc=)")
                        .doesNotContainPattern("\\son[a-z]+=\"").doesNotContain("style=\"").doesNotContain("<style");
            }
        }
    }
}
