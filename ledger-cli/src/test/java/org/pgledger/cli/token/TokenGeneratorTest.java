package org.pgledger.cli.token;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** INS-013: the tokens come from 32 bytes of a {@link SecureRandom}, in the URL-safe base64 alphabet. */
class TokenGeneratorTest {

    @Test
    @DisplayName("INS-013 a token is 43 characters of the URL-safe alphabet (well over the 16 the servers require) and no two tokens of a run are equal")
    void ins013_shapeAndUniqueness() {
        TokenGenerator generator = new TokenGenerator(new SecureRandom());
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            char[] token = generator.generate();
            assertThat(String.valueOf(token)).matches("[A-Za-z0-9_-]{43}");
            seen.add(String.valueOf(token));
        }

        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("INS-013 a token is exactly 32 bytes asked of the injected SecureRandom and nothing else")
    void ins013_theBytesComeFromTheInjectedRandom() {
        int[] requested = new int[1];
        SecureRandom fixed = new SecureRandom() {

            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                requested[0] += bytes.length;
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) i;
                }
            }
        };
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }

        char[] token = new TokenGenerator(fixed).generate();

        assertThat(requested[0]).isEqualTo(32);
        assertThat(String.valueOf(token)).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(expected));
    }

    @Test
    @DisplayName("INS-013 the token code uses no java.util.Random and no Math.random: the only source is a SecureRandom that is handed in")
    void ins013_noWeakRandomSource() {
        noClasses().that().resideInAPackage("org.pgledger.cli.token..").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Random")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
                .orShould().callMethod(Math.class, "random")
                .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.pgledger.cli.token"));
    }
}
