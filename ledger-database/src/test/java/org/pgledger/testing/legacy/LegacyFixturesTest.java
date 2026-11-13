package org.pgledger.testing.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FUP-FIX-01: the canonical categories of the legacy fixture are a class path resource of the test fixtures, so a test reads
 * them whatever its working directory is (it used to open a path of the repository relative to the module directory).
 */
class LegacyFixturesTest {

    private static final List<String> CODES = List.of("ARC", "CON", "DAT", "DENY", "DIAG", "DOC", "EXC", "GATE", "HOST", "PKG",
            "RES", "SEC", "TST", "VND");

    @Test
    @DisplayName("FUP-FIX-01 the fixture is the class path resource category-legacy-fixture.json next to LegacyFixtures")
    void theFixtureIsAClassPathResource() {
        assertThat(LegacyFixtures.class.getResource("category-legacy-fixture.json")).isNotNull();
    }

    @Test
    @DisplayName("FUP-FIX-01 the 14 canonical codes are read, each with a Korean name and definition")
    void theCanonicalCategoriesAreRead() {
        List<LegacyFixtures.Category> categories = LegacyFixtures.categories();

        assertThat(categories).extracting(LegacyFixtures.Category::code).containsExactlyElementsOf(CODES);
        assertThat(categories).allSatisfy(category -> {
            assertThat(category.name()).containsPattern("[\\uAC00-\\uD7A3]");
            assertThat(category.definition()).containsPattern("[\\uAC00-\\uD7A3]");
        });
    }
}
