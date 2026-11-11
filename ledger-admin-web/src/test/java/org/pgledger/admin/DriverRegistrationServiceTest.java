package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.pgledger.admin.setup.DriverRegistrationFile;
import org.pgledger.admin.setup.DriverRegistrationService;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.TestOwners;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * DRV-005, DRV-016, DRV-020 and ADM-060, ADM-061, ADM-062 as far as the admin does them: the URL the driver must
 * accept, a registration that replaces the file, and the one allowed directory of the driver jars with one unified
 * refusal. The service is made by Spring from its beans (an {@link ApplicationContextRunner}), the way the application
 * makes it.
 */
class DriverRegistrationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

    /** The one text the client gets for every refused jar or class (ADM-060). */
    private static final String REFUSED = "the jar or the class cannot be used for a driver";

    /** What a test does with the service. */
    private interface Body {
        void accept(DriverRegistrationService service) throws Exception;
    }

    private static Path h2Jar() throws URISyntaxException {
        return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** The registered paths are real paths (symbolic links resolved), so the tests compare with real paths. */
    private static Path realDirectory(Path directory, String name) throws IOException {
        return OwnerOnlyFiles.restrict(Files.createDirectories(directory.toRealPath().resolve(name))).toRealPath();
    }

    private static Path h2JarIn(Path directory, String name) throws IOException, URISyntaxException {
        return OwnerOnlyFiles.copy(h2Jar(), directory.resolve(name)).toRealPath();
    }

    private static DriverRegistration register(DriverRegistrationService service, String jar, String className) {
        return service.register(jar, className, TestOwners.OWNER);
    }

    private static LedgerAdminProperties adminProperties(Path registrationFile, Path driverDirectory) {
        Map<String, String> source = Map.of("ledger.admin.driver-file", registrationFile.toString(),
                "ledger.admin.driver-directory", driverDirectory.toString());
        return new Binder(new MapConfigurationPropertySource(source)).bind("ledger.admin",
                Bindable.of(LedgerAdminProperties.class)).get();
    }

    private static void withService(Path registrationFile, Path driverDirectory, String url, Body body) {
        YAMLMapper yaml = YAMLMapper.builder()
                .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.KEBAB_CASE)
                .disable(tools.jackson.dataformat.yaml.YAMLWriteFeature.SPLIT_LINES).build();
        new ApplicationContextRunner()
                .withBean(DriverRegistrationFile.class, () -> new DriverRegistrationFile(yaml, registrationFile))
                .withBean(LedgerJdbcProperties.class, () -> new LedgerJdbcProperties(url, null, null, 1,
                        new DriverRegistration(null, null, null, null)))
                .withBean(Clock.class, () -> CLOCK)
                .withBean(LedgerAdminProperties.class, () -> adminProperties(registrationFile, driverDirectory))
                .withBean(DriverRegistrationService.class)
                .run(context -> body.accept(context.getBean(DriverRegistrationService.class)));
    }

    /** The refusal every unusable jar or class gets: DRIVER_INVALID with the one fixed text and nothing else. */
    private static void assertRefused(Throwable thrown, Path... mustNotAppear) {
        assertThat(thrown).isInstanceOfSatisfying(InfrastructureException.class, e -> {
            assertThat(e.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
            assertThat(e.args()).isEqualTo(List.of(REFUSED));
            assertThat(e.getCause()).as("no cause that a handler could print").isNull();
            for (Path path : mustNotAppear) {
                assertThat(e.getMessage()).doesNotContain(path.toString());
            }
        });
    }

    private static void linkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            throw new TestAbortedException("this file system cannot make symbolic links here: " + e, e);
        }
    }

    @Test
    @DisplayName("DRV-005: a driver that does not accept the configured JDBC URL is DRIVER_INVALID with the unified text, "
            + "without the password, and nothing is stored")
    void theDriverMustAcceptTheUrl(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path jar = h2JarIn(realDirectory(directory, "drivers"), "h2.jar");

        withService(file, jar.getParent(), "jdbc:postgresql://db.example/ledger?password=secret-value", service -> {
            assertThatThrownBy(() -> register(service, jar.toString(), "org.h2.Driver"))
                    .isInstanceOfSatisfying(InfrastructureException.class, e -> {
                        assertThat(e.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
                        assertThat(e.args()).isEqualTo(List.of(REFUSED));
                        assertThat(e.args().toString()).doesNotContain("secret-value");
                    });
        });
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("DRV-016/DRV-020: a second registration replaces the first atomically; no temporary file is left")
    void aSecondRegistrationReplacesTheFirst(@TempDir Path directory) throws Exception {
        Path configuration = realDirectory(directory, "config");
        Path file = configuration.resolve("jdbc-driver.yaml");
        Path jar = h2JarIn(realDirectory(directory, "drivers"), "h2.jar");

        withService(file, jar.getParent(), "jdbc:h2:mem:x", service -> {
            register(service, jar.toString(), "org.h2.Driver");
            String first = Files.readString(file);
            DriverRegistration second = register(service, jar.toString(), "org.h2.Driver");

            assertThat(Files.readString(file)).isEqualTo(first).contains("registered-at: \"2026-11-05T10:00:00Z\"");
            assertThat(second.registeredAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        });
        try (var entries = Files.list(configuration)) {
            assertThat(entries.toList()).containsExactly(file);
        }
    }

    @Test
    @DisplayName("DRV-016: the state tells that a restart is needed while the registered driver differs from the one in use")
    void restartIsNeededUntilTheDriverInUseIsTheRegisteredOne(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path jar = h2JarIn(realDirectory(directory, "drivers"), "h2.jar");

        withService(file, jar.getParent(), "jdbc:h2:mem:x", service -> {
            assertThat(service.state().registered()).isEmpty();
            assertThat(service.state().restartRequired()).isFalse();
            register(service, jar.toString(), "org.h2.Driver");

            assertThat(service.state().registered()).isPresent();
            assertThat(service.state().restartRequired()).isTrue();
        });
    }

    @Test
    @DisplayName("ADM-060: a jar inside the allowed directory is registered, with its real path")
    void adm060_aJarInsideTheAllowedDirectoryIsRegistered(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path jar = h2JarIn(OwnerOnlyFiles.restrict(Files.createDirectories(drivers.resolve("vendor"))), "h2.jar");

        withService(file, drivers, "jdbc:h2:mem:x", service -> {
            DriverRegistration registration = register(service, jar.toString(), "org.h2.Driver");

            assertThat(registration.jar()).isEqualTo(jar);
            assertThat(file).exists();
        });
    }

    @Test
    @DisplayName("ADM-060: a jar outside the allowed directory is refused with the unified text and nothing is stored")
    void adm060_aJarOutsideTheAllowedDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path outside = h2JarIn(realDirectory(directory, "outside"), "h2.jar");

        withService(file, drivers, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, outside.toString(),
                        "org.h2.Driver")), outside, drivers));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a sibling directory whose name starts with the allowed name is outside")
    void adm060_aSiblingWithTheSamePrefixIsOutside(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path sibling = h2JarIn(realDirectory(directory, "drivers-evil"), "h2.jar");

        withService(file, drivers, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, sibling.toString(),
                        "org.h2.Driver")), sibling));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a path with '..' that leaves the allowed directory is refused")
    void adm060_dotDotThatLeavesTheDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        h2JarIn(realDirectory(directory, "outside"), "h2.jar");
        Path escaping = drivers.resolve("..").resolve("outside").resolve("h2.jar");

        withService(file, drivers, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, escaping.toString(),
                        "org.h2.Driver"))));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a symbolic link in the allowed directory that points outside is refused (skipped where links "
            + "cannot be made)")
    void adm060_aSymbolicLinkThatPointsOutsideIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path outside = h2JarIn(realDirectory(directory, "outside"), "h2.jar");
        Path link = drivers.resolve("link.jar");
        linkOrSkip(link, outside);

        withService(file, drivers, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, link.toString(),
                        "org.h2.Driver")), outside));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a missing allowed directory refuses every registration with the unified text")
    void adm060_aMissingAllowedDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path jar = h2JarIn(realDirectory(directory, "elsewhere"), "h2.jar");
        Path missing = directory.toRealPath().resolve("no-such-directory");

        withService(file, missing, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, jar.toString(),
                        "org.h2.Driver")), missing));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a jar that does not exist, a directory and a relative path are refused with the same text")
    void adm060_aMissingJarADirectoryAndARelativePathAreRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path missing = drivers.resolve("no-such.jar");
        Path subdirectory = Files.createDirectories(drivers.resolve("a-directory.jar"));

        withService(file, drivers, "jdbc:h2:mem:x", service -> {
            assertRefused(catchThrowable(() -> register(service, missing.toString(),
                    "org.h2.Driver")), missing);
            assertRefused(catchThrowable(() -> register(service, 
                    subdirectory.toString(), "org.h2.Driver")), subdirectory);
            assertRefused(catchThrowable(() -> register(service, "h2.jar",
                    "org.h2.Driver")));
        });
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a jar that cannot be read is refused with the same text (skipped where chmod 000 leaves it "
            + "readable)")
    void adm060_anUnreadableJarIsRefused(@TempDir Path directory) throws Exception {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path jar = h2JarIn(drivers, "h2.jar");
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(jar), "the process can read the file anyway (a privileged user)");

        withService(file, drivers, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, jar.toString(),
                        "org.h2.Driver")), jar));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: whatever the loader refuses inside the allowed directory has the same text: not an archive, "
            + "class absent, class that is not a driver")
    void adm060_loaderFailuresHaveTheSameText(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path jar = h2JarIn(drivers, "h2.jar");
        Path notAnArchive = OwnerOnlyFiles.write(drivers.resolve("text.jar"),
                    "this is text, not an archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        withService(file, drivers, "jdbc:h2:mem:x", service -> {
            assertRefused(catchThrowable(() -> register(service, 
                    notAnArchive.toString(), "org.h2.Driver")), notAnArchive);
            assertRefused(catchThrowable(() -> register(service, jar.toString(),
                    "org.example.NoSuchDriver")), jar);
            assertRefused(catchThrowable(() -> register(service, jar.toString(),
                    "org.h2.engine.Constants")), jar);
        });
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a blank jar and a blank class keep their own messages (they are what the user typed)")
    void adm060_blankFieldsKeepTheirMessages(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");

        withService(file, drivers, "jdbc:h2:mem:x", service -> {
            assertThatThrownBy(() -> register(service, "  ", "org.h2.Driver"))
                    .isInstanceOfSatisfying(InfrastructureException.class, e -> assertThat(e.args())
                            .isEqualTo(List.of("the jar path is missing")));
            assertThatThrownBy(() -> register(service, "/some/where.jar", " "))
                    .isInstanceOfSatisfying(InfrastructureException.class, e -> assertThat(e.args())
                            .isEqualTo(List.of("the driver class name is missing")));
        });
    }

    @Test
    @DisplayName("ADM-061: a jar path with ${ is refused with the unified text and nothing is stored")
    void adm061_aPathWithAPlaceholderIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path jar = h2JarIn(OwnerOnlyFiles.restrict(Files.createDirectories(drivers.resolve("${x}"))), "h2.jar");

        withService(file, drivers, "jdbc:h2:mem:x", service -> assertRefused(
                catchThrowable(() -> register(service, jar.toString(),
                        "org.h2.Driver")), jar));
        assertThat(file).doesNotExist();
    }
}
