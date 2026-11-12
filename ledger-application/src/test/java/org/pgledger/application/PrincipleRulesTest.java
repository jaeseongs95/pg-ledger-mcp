package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.testing.PrincipleRules;
import org.pgledger.testing.PrincipleRules.Finding;

/**
 * ARC-PRN-07: each rule of the development principles (ARC-PRN-01 .. 06) finds exactly what is planted for it, in
 * {@code principlefixture} (bytecode rules) and in temporary sources (source rules), and nothing of the clean counterparts;
 * and the allow list works as it is meant to (a reason, and an entry that is used).
 */
class PrincipleRulesTest {

    private static final JavaClasses FIXTURE = new ClassFileImporter()
            .importPackages("org.pgledger.application.principlefixture");

    @TempDir
    Path sources;

    private static List<String> keys(List<Finding> findings) {
        return findings.stream().map(Finding::key).toList();
    }

    @Test
    @DisplayName("ARC-PRN-01 a @Bean method of our own type is found, one of an external type is not")
    void beanOfOwnType() {
        assertThat(keys(PrincipleRules.beanMethodsOfOwnTypes(FIXTURE))).containsExactly("Planted$Wiring.planted");
    }

    @Test
    @DisplayName("ARC-PRN-02 an Optional field and an Optional parameter are found, an Optional answer is not")
    void optionalStored() {
        assertThat(keys(PrincipleRules.optionalStoredOrPassed(FIXTURE))).containsExactlyInAnyOrder("Planted.maybe",
                "Planted.take");
    }

    @Test
    @DisplayName("ARC-PRN-03 Optional.get() is found, map and orElse are not")
    void optionalGet() {
        assertThat(keys(PrincipleRules.optionalGet(FIXTURE))).containsExactly("Planted.unwrap");
    }

    @Test
    @DisplayName("ARC-PRN-05 a transaction that writes a file, itself, through a method of ours or in a lambda it holds, is found; one that only reads is not")
    void fileWriteInTransaction() {
        assertThat(keys(PrincipleRules.fileWritesInTransactions(FIXTURE))).containsExactlyInAnyOrder("Planted.writeDirect",
                "Planted.writeIndirect", "Planted.writeInALambda");
    }

    private Path source(String name, String text) throws IOException {
        return Files.writeString(sources.resolve(name), text);
    }

    @Test
    @DisplayName("ARC-PRN-04 orElse(null) is found (also with blanks inside); orElse of a value, a comment and a string are not")
    void orElseNull() throws IOException {
        source("Planted.java", "class Planted { Object a(java.util.Optional<String> o) { return o.orElse( null ); } }");
        source("Clean.java", "class Clean { Object a(java.util.Optional<String> o) { // o.orElse(null)\n"
                + "  String text = \"o.orElse(null)\"; return o.orElse(\"\"); } }");

        assertThat(keys(PrincipleRules.orElseNull(sources))).containsExactly("Planted.java");
    }

    @Test
    @DisplayName("ARC-PRN-06 equals of two variables is found (also through calls); a literal, a constant, a type name, equalsIgnoreCase, a comment and a string are not")
    void variableEquals() throws IOException {
        source("Planted.java", "class Planted { boolean a(Object x, Object y) { return x.equals(y); } }");
        source("PlantedCalls.java", "class PlantedCalls { boolean a(Entry e, Entry f) { return e.principal().equals(f.principal()); } }");
        source("Clean.java", "class Clean { static final String OWNER = \"o\"; boolean a(String x, String y) {\n"
                + "  // x.equals(y)\n  String text = \"x.equals(y)\";\n"
                + "  return Objects.equals(x, y) || \"lit\".equals(x) || OWNER.equals(x) || Kind.OWNER.equals(x) || java.util.Arrays.equals(x, y)\n"
                + "      || Boolean.TRUE.equals(x) || Clean.class.equals(x) || x.equalsIgnoreCase(y) || x == y; } }");

        assertThat(keys(PrincipleRules.variableEquals(sources))).containsExactlyInAnyOrder("Planted.java",
                "PlantedCalls.java");
    }

    @Test
    @DisplayName("ARC-PRN-07 the allow list: a finding with an entry is allowed, an entry without a reason and an entry that nothing uses are problems")
    void allowList() {
        List<Finding> findings = List.of(new Finding("A.a", "found a"), new Finding("B.b", "found b"));

        assertThat(PrincipleRules.problems("ARC-PRN-01", findings, Map.of("A.a", "a reason", "B.b", "another reason")))
                .isEmpty();
        assertThat(PrincipleRules.problems("ARC-PRN-01", findings, Map.of("A.a", "a reason")))
                .singleElement().asString().contains("B.b").contains("found b");
        assertThat(PrincipleRules.problems("ARC-PRN-01", findings, Map.of("A.a", " ", "B.b", "another reason")))
                .singleElement().asString().contains("A.a").contains("no reason");
        assertThat(PrincipleRules.problems("ARC-PRN-01", findings,
                Map.of("A.a", "a reason", "B.b", "another reason", "C.c", "a reason")))
                .singleElement().asString().contains("C.c").contains("remove the entry");
    }
}
