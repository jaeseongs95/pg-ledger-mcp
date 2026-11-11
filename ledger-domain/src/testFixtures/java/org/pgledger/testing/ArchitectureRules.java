package org.pgledger.testing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.AccessibleObject;
import java.time.Clock;
import java.util.Date;
import java.util.Set;
import java.util.TimeZone;

/**
 * Architecture rules shared by the modules. Each method is one rule of the test catalogue (ARC-*); a module
 * test imports its own production classes and checks the rules that apply to it.
 */
public final class ArchitectureRules {

    private ArchitectureRules() {
    }

    /** The production classes (no test classes) of a package tree. */
    public static JavaClasses productionClasses(String rootPackage) {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(rootPackage);
    }

    /** ARC-001. */
    public static ArchRule noLombok() {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage("lombok..");
    }

    /** ARC-002 (bytecode part): no setAccessible. */
    public static ArchRule noSetAccessible() {
        return noClasses().should().callMethodWhere(DescribedPredicate.describe("calls setAccessible or trySetAccessible",
                call -> (call.getTarget().getName().equals("setAccessible")
                        || call.getTarget().getName().equals("trySetAccessible"))
                        && call.getTargetOwner().isAssignableTo(AccessibleObject.class)));
    }

    private static final Set<String> REFLECTIVE_CLASS_METHODS = Set.of("forName", "newInstance",
            "getDeclaredConstructor", "getConstructor", "getMethod", "getDeclaredMethod", "getDeclaredField",
            "getField", "getDeclaredMethods", "getMethods", "getDeclaredFields", "getDeclaredConstructors");

    /** ARC-003: {@code java.lang.reflect} and reflective {@code Class} calls only in the listed packages. */
    public static ArchRule reflectionOnlyIn(String... allowedPackages) {
        DescribedPredicate<JavaMethodCall> reflectiveCall = DescribedPredicate.describe(
                "calls a reflective method of java.lang.Class",
                call -> call.getTargetOwner().isEquivalentTo(Class.class)
                        && REFLECTIVE_CLASS_METHODS.contains(call.getTarget().getName()));
        return noClasses().that().resideOutsideOfPackages(allowedPackages)
                .should().dependOnClassesThat().resideInAPackage("java.lang.reflect..")
                .orShould().callMethodWhere(reflectiveCall);
    }

    /** ARC-004. */
    public static ArchRule noProcessExecution() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .orShould().callMethod(Runtime.class, "exec", String.class)
                .orShould().callMethod(Runtime.class, "exec", String[].class);
    }

    /** ARC-006. */
    public static ArchRule noStandardStreamsAndOtherLoggers() {
        return noClasses().should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .orShould().callMethod(Throwable.class, "printStackTrace")
                .orShould().dependOnClassesThat().resideInAnyPackage("java.util.logging..", "org.apache.log4j..",
                        "org.apache.logging..", "org.apache.commons.logging..");
    }

    /** ARC-007 (bytecode part): no System.getenv and no @Value. */
    public static ArchRule noEnvironmentAccessAndValueAnnotation() {
        return noClasses().should().callMethod(System.class, "getenv")
                .orShould().callMethod(System.class, "getenv", String.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value");
    }

    /** ARC-010. */
    public static ArchRule noJsonTreeModels() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName("tools.jackson.databind.JsonNode")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("tools.jackson.databind.node.ObjectNode")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.fasterxml.jackson.databind.JsonNode");
    }

    /** ARC-033 (bytecode part): no decision by the path separator and no operating system switch of a test. */
    public static ArchRule noOperatingSystemDetection() {
        return noClasses().should().accessField(java.io.File.class, "separator")
                .orShould().accessField(java.io.File.class, "separatorChar")
                .orShould().accessField(java.io.File.class, "pathSeparator")
                .orShould().accessField(java.io.File.class, "pathSeparatorChar")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.condition.OS")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.condition.EnabledOnOs")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.condition.DisabledOnOs");
    }

    /** ARC-018. */
    public static ArchRule noThreadLocalAndNoMutableStatics() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal");
    }

    /** ARC-018, second half. */
    public static ArchRule staticFieldsAreFinal() {
        return fields().that().areStatic().should().beFinal();
    }

    /** ARC-019 / ARC-024: no time without the injected Clock, no default zone, no legacy date classes. */
    public static ArchRule noAmbientTime(String... allowedPackages) {
        DescribedPredicate<JavaMethodCall> nowWithoutClock = DescribedPredicate.describe(
                "calls a java.time now(..) overload that does not take a Clock",
                call -> call.getTarget().getName().equals("now")
                        && call.getTargetOwner().getPackageName().equals("java.time")
                        && call.getTarget().getRawParameterTypes().stream()
                                .noneMatch(p -> p.isAssignableTo(Clock.class)));
        return noClasses().that().resideOutsideOfPackages(allowedPackages)
                .should().callMethodWhere(nowWithoutClock)
                .orShould().callMethod(java.time.ZoneId.class, "systemDefault")
                .orShould().callMethod(Clock.class, "systemDefaultZone")
                .orShould().callMethod(TimeZone.class, "getDefault")
                .orShould().callMethod(TimeZone.class, "setDefault", TimeZone.class)
                .orShould().callMethod(java.util.Calendar.class, "getInstance")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(Date.class.getName());
    }

    /** ARC-009: the domain depends on nothing of web, Jackson or the database module. */
    public static ArchRule domainIsIndependent() {
        return noClasses().that().resideInAPackage("org.pgledger.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml..",
                        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                        "org.pgledger.database..", "org.springframework.jdbc..");
    }

    /** ARC-022 / DOM-035: aggregates are records, enums or interfaces. */
    public static ArchRule modelTypesAreRecordsEnumsOrInterfaces() {
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes().that()
                .resideInAPackage("org.pgledger.domain.model")
                .should(new com.tngtech.archunit.lang.ArchCondition<JavaClass>("be a record, an enum or an interface") {
                    @Override
                    public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                        boolean ok = item.isRecord() || item.isEnum() || item.isInterface();
                        events.add(new com.tngtech.archunit.lang.SimpleConditionEvent(item, ok,
                                item.getName() + (ok ? " is fine" : " is not a record, enum or interface")));
                    }
                });
    }

    /** ARC-022 / DOM-035: no boolean or zone-bearing time component in an aggregate (batch operations excepted). */
    public static ArchRule aggregatesHaveNoBooleanNorLocalTime() {
        return noFields().that().areDeclaredInClassesThat().resideInAPackage("org.pgledger.domain.model")
                .and().areDeclaredInClassesThat().haveNameNotMatching(".*BatchOp.*")
                .should().haveRawType(boolean.class)
                .orShould().haveRawType(Boolean.class)
                .orShould().haveRawType(java.time.LocalDateTime.class)
                .orShould().haveRawType(java.time.ZonedDateTime.class)
                .orShould().haveRawType(java.time.OffsetDateTime.class)
                .orShould().haveRawType(java.time.ZoneId.class);
    }
}
