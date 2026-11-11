package org.pgledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HK-018 (contents) and the static half of HK-021 (manifest, class file version). */
class JarContentTest {

    private static final Path JAR = Path.of(System.getProperty("ledger.hook.jar"));

    /** Everything a hook jar may hold; anything else fails the test. A change of this list needs review. */
    private static final Pattern SIGNATURE_FILE = Pattern.compile("META-INF/.*\\.(SF|DSA|RSA)");
    private static final Pattern SECRET_NAME = Pattern.compile("secret|token|credential|password",
            Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("org/pgledger/hook/.*"),
            Pattern.compile("tools/jackson/(core|databind)/.*"),
            Pattern.compile("com/fasterxml/jackson/annotation/.*"),
            Pattern.compile("META-INF/MANIFEST\\.MF"),
            Pattern.compile("META-INF/services/tools\\.jackson\\.(core\\.TokenStreamFactory|databind\\.ObjectMapper)"),
            Pattern.compile("META-INF/versions/\\d+/tools/jackson/core/.*"),
            Pattern.compile("META-INF/third-party/[A-Za-z0-9._-]+/[A-Za-z0-9._-]*(LICENSE|NOTICE)[A-Za-z0-9._-]*"));

    private static List<String> entries() throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(JAR.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("HK-018 the jar holds only the module's classes, Jackson (core, annotations, databind) and the licence texts")
    void onlyModuleAndJackson() throws IOException {
        List<String> names = entries();
        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name -> assertThat(ALLOWED.stream().anyMatch(p -> p.matcher(name).matches())).as(name).isTrue());
        assertThat(names).anyMatch(n -> n.startsWith("org/pgledger/hook/Main"));
        assertThat(names).anyMatch(n -> n.startsWith("tools/jackson/core/"));
        assertThat(names).anyMatch(n -> n.startsWith("tools/jackson/databind/"));
        assertThat(names).anyMatch(n -> n.startsWith("com/fasterxml/jackson/annotation/"));
    }

    @Test
    @DisplayName("HK-018 no Spring, no Spring Boot loader, no JDBC driver, no secret or configuration file, no signature or module descriptor")
    void nothingElse() throws IOException {
        List<String> names = entries();
        assertThat(names).noneMatch(n -> n.startsWith("org/springframework/"))
                .noneMatch(n -> n.startsWith("org/postgresql/") || n.startsWith("org/h2/") || n.startsWith("com/mysql/")
                        || n.startsWith("org/mariadb/") || n.startsWith("oracle/") || n.startsWith("com/microsoft/"))
                .noneMatch(n -> n.startsWith("BOOT-INF/") || n.startsWith("META-INF/spring"))
                .noneMatch(n -> n.endsWith(".properties") || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".xml")
                        || n.endsWith(".toml") || n.endsWith(".env") || n.endsWith(".pem") || n.endsWith(".key") || n.endsWith(".json"))
                .noneMatch(n -> SIGNATURE_FILE.matcher(n).matches())
                .noneMatch(n -> n.endsWith("module-info.class"))
                // class names such as JsonToken are fine; a non-class resource with such a name is not
                .noneMatch(n -> !n.endsWith(".class") && !n.startsWith("META-INF/services/") && SECRET_NAME.matcher(n).find());
    }

    @Test
    @DisplayName("HK-021 the manifest names Main-Class, and the jar is the only thing needed to run it (no Class-Path)")
    void manifest() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue("Main-Class")).isEqualTo("org.pgledger.hook.Main");
            assertThat(main.getValue("Class-Path")).isNull();
            // jackson-core ships Java 17 specific classes under META-INF/versions
            assertThat(main.getValue("Multi-Release")).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("HK-021 the module's classes are Java 25 class files (major 69): older JDKs refuse them with UnsupportedClassVersionError")
    void classFilesNeedJava25() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            List<JarEntry> own = Collections.list(jar.entries()).stream()
                    .filter(e -> e.getName().startsWith("org/pgledger/hook/") && e.getName().endsWith(".class")).toList();
            assertThat(own).isNotEmpty();
            for (JarEntry entry : own) {
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] header = in.readNBytes(8);
                    int major = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                    assertThat(major).as(entry.getName()).isEqualTo(69);
                }
            }
        }
    }
}
