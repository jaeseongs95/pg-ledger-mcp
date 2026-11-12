package org.pgledger.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DRV-013 and SET-041: the built jars of the three applications are looked into, every nested jar and every class. The MCP
 * server and the admin web carry no JDBC driver at all: no {@code META-INF/services/java.sql.Driver}, no class that implements
 * {@code java.sql.Driver}, no package of a driver. The command line tool carries exactly one, the H2 driver that reads the seed
 * bundle (the data of a first setup, moved into the DBMS of the operator; H2 is never the ledger database), nothing of another
 * DBMS, and it carries the seed bundle. The class files are read with the JDK's class file API: the interfaces of a class are
 * looked at, not its name.
 */
class DriverFreeJarsTest {

    /** The packages a JDBC driver of a DBMS lives in (the list is the one of the root build's forbidden runtime, by package). */
    private static final Pattern DRIVER_PACKAGE = Pattern.compile(
            "(org/postgresql|org/h2|com/mysql|org/mariadb|oracle/jdbc|com/microsoft/sqlserver|org/sqlite|org/apache/derby|org/hsqldb|com/ibm/db2)/.*");
    private static final String SERVICE_FILE = "META-INF/services/java.sql.Driver";
    private static final String SEED_BUNDLE = "BOOT-INF/classes/ledger-seed.mv.db";

    /** What the jars hold that a driver would be. */
    private record Findings(List<String> serviceFiles, List<String> driverClasses, List<String> driverPackages, boolean hasSeedBundle) {
    }

    private static Path jar(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the test property " + property + " is not set (ledger-system-test/build.gradle.kts passes it)");
        }
        return Path.of(value);
    }

    private static Findings scan(Path outer) throws IOException {
        List<String> services = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        boolean seed = false;
        try (JarFile jar = new JarFile(outer.toFile())) {
            for (JarEntry entry : jar.stream().toList()) {
                String name = entry.getName();
                if (name.equals(SEED_BUNDLE)) {
                    seed = true;
                }
                if (name.endsWith(".jar")) {
                    try (JarInputStream nested = new JarInputStream(new ByteArrayInputStream(jar.getInputStream(entry).readAllBytes()))) {
                        for (JarEntry inner = nested.getNextJarEntry(); inner != null; inner = nested.getNextJarEntry()) {
                            look(name + "!" + inner.getName(), inner.getName(), nested.readAllBytes(), services, classes, packages);
                        }
                    }
                } else if (!entry.isDirectory()) {
                    look(name, name, jar.getInputStream(entry).readAllBytes(), services, classes, packages);
                }
            }
        }
        return new Findings(services, classes, packages, seed);
    }

    private static void look(String shown, String name, byte[] bytes, List<String> services, List<String> classes, List<String> packages) {
        String path = name.startsWith("BOOT-INF/classes/") ? name.substring("BOOT-INF/classes/".length()) : name;
        if (SERVICE_FILE.equals(name)) {
            services.add(shown + " -> " + new String(bytes, StandardCharsets.UTF_8).strip());
        }
        if (DRIVER_PACKAGE.matcher(path).matches()) {
            packages.add(shown);
        }
        if (name.endsWith(".class") && implementsDriver(bytes)) {
            classes.add(shown);
        }
    }

    private static boolean implementsDriver(byte[] classFile) {
        return ClassFile.of().parse(classFile).interfaces().stream().anyMatch(type -> "java/sql/Driver".equals(type.asInternalName()));
    }

    @Test
    @DisplayName("DRV-013 the jars of the MCP server and of the admin web hold no JDBC driver: no service file, no class that implements java.sql.Driver, no package of a driver")
    void theServersCarryNoDriver() throws IOException {
        for (String property : List.of("ledger.mcp.jar", "ledger.admin.jar")) {
            Findings found = scan(jar(property));

            assertThat(found.serviceFiles()).as(property + " service files").isEmpty();
            assertThat(found.driverClasses()).as(property + " driver classes").isEmpty();
            assertThat(found.driverPackages()).as(property + " driver packages").isEmpty();
        }
    }

    @Test
    @DisplayName("DRV-013 (changed by SETUP, SET-041) the jar of the command line tool holds one driver, the H2 driver of the seed bundle, nothing of another DBMS, and the seed bundle itself")
    void theCommandLineToolCarriesTheDriverOfTheSeedAndNothingElse() throws IOException {
        Findings found = scan(jar("ledger.cli.jar"));

        assertThat(found.hasSeedBundle()).as("the seed bundle is packed").isTrue();
        assertThat(found.serviceFiles()).hasSize(1).allSatisfy(file -> assertThat(file).endsWith("-> org.h2.Driver"));
        assertThat(found.driverClasses()).isNotEmpty().allSatisfy(name -> assertThat(name).contains("org/h2/"));
        assertThat(found.driverPackages()).isNotEmpty().allSatisfy(name -> assertThat(name).contains("org/h2/"));
    }
}
