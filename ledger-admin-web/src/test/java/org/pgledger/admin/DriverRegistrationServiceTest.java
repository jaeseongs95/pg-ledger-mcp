package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.pgledger.admin.setup.DriverRegistrationService;
import org.pgledger.admin.support.TestDriverJar;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistrar;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.TestOwners;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    /** The registered paths are real paths (symbolic links resolved), so the tests compare with real paths. */
    private static Path realDirectory(Path directory, String name) throws IOException {
        return OwnerOnlyFiles.restrict(Files.createDirectories(directory.toRealPath().resolve(name))).toRealPath();
    }

    /** A copy of the PostgreSQL driver jar (the one that the build resolved) in {@code directory}, as its real path. */
    private static Path driverJarIn(Path directory, String name) throws IOException {
        return OwnerOnlyFiles.copy(TestDriverJar.jar(), directory.resolve(name)).toRealPath();
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

    /** The beans the service needs in setup mode (no audit writer, no transaction manager). */
    private static ApplicationContextRunner runner(Path registrationFile, Path driverDirectory, String url) {
        return new ApplicationContextRunner()
                .withBean(DriverRegistrationFile.class,
                        () -> new DriverRegistrationFile(new DriverRegistrationFile.Location(registrationFile)))
                .withBean(LedgerJdbcProperties.class, () -> new LedgerJdbcProperties(url, null, null, 1,
                        new DriverRegistration(null, null, null, null)))
                .withBean(Clock.class, () -> CLOCK)
                .withBean(LedgerAdminProperties.class, () -> adminProperties(registrationFile, driverDirectory))
                .withBean(DriverLoader.class)
                .withBean(DriverRegistrar.class, () -> new DriverRegistrar(new DriverLoader(), url, driverDirectory))
                .withBean(DriverRegistrationService.class);
    }

    private static void withService(Path registrationFile, Path driverDirectory, String url, Body body) {
        runner(registrationFile, driverDirectory, url)
                .run(context -> body.accept(context.getBean(DriverRegistrationService.class)));
    }

    /** The service of a console: with the writer of the audit row and the transaction manager of its database. */
    private static void withAuditedService(Path registrationFile, Path driverDirectory, OwnerAuditWriter writer,
            PlatformTransactionManager transactions, Body body) {
        runner(registrationFile, driverDirectory, TestDriverJar.url())
                .withBean(OwnerAuditWriter.class, () -> writer)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .run(context -> body.accept(context.getBean(DriverRegistrationService.class)));
    }

    /**
     * A transaction manager that does what Spring's does around a commit (it runs the synchronizations after the
     * commit and after the completion) with nothing behind it: {@code commit} is what the test says it is.
     */
    private static final class CommitOf extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        private final transient Runnable atCommit;

        CommitOf(Runnable atCommit) {
            this.atCommit = atCommit;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // nothing to begin: there is no database
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            atCommit.run();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }

    /** The files of a directory by name, to see that no temporary file is left. */
    private static List<String> names(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
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
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");

        withService(file, jar.getParent(), "jdbc:example://db.example/ledger?password=secret-value", service -> {
            assertThatThrownBy(() -> register(service, jar.toString(), TestDriverJar.DRIVER_CLASS))
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
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");

        withService(file, jar.getParent(), TestDriverJar.url(), service -> {
            register(service, jar.toString(), TestDriverJar.DRIVER_CLASS);
            String first = Files.readString(file);
            DriverRegistration second = register(service, jar.toString(), TestDriverJar.DRIVER_CLASS);

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
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");

        withService(file, jar.getParent(), TestDriverJar.url(), service -> {
            assertThat(service.state().registered()).isInstanceOf(DriverRegistrationService.Registration.None.class);
            assertThat(service.state().restartRequired()).isFalse();
            register(service, jar.toString(), TestDriverJar.DRIVER_CLASS);

            assertThat(service.state().registered()).isInstanceOf(DriverRegistrationService.Registration.Present.class);
            assertThat(service.state().restartRequired()).isTrue();
        });
    }

    @Test
    @DisplayName("ADM-060: a jar inside the allowed directory is registered, with its real path")
    void adm060_aJarInsideTheAllowedDirectoryIsRegistered(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path jar = driverJarIn(OwnerOnlyFiles.restrict(Files.createDirectories(drivers.resolve("vendor"))), "postgresql.jar");

        withService(file, drivers, TestDriverJar.url(), service -> {
            DriverRegistration registration = register(service, jar.toString(), TestDriverJar.DRIVER_CLASS);

            assertThat(registration.jar()).isEqualTo(jar);
            assertThat(file).exists();
        });
    }

    @Test
    @DisplayName("ADM-060: a jar outside the allowed directory is refused with the unified text and nothing is stored")
    void adm060_aJarOutsideTheAllowedDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path outside = driverJarIn(realDirectory(directory, "outside"), "postgresql.jar");

        withService(file, drivers, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, outside.toString(),
                        TestDriverJar.DRIVER_CLASS)), outside, drivers));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a sibling directory whose name starts with the allowed name is outside")
    void adm060_aSiblingWithTheSamePrefixIsOutside(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path sibling = driverJarIn(realDirectory(directory, "drivers-evil"), "postgresql.jar");

        withService(file, drivers, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, sibling.toString(),
                        TestDriverJar.DRIVER_CLASS)), sibling));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a path with '..' that leaves the allowed directory is refused")
    void adm060_dotDotThatLeavesTheDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        driverJarIn(realDirectory(directory, "outside"), "postgresql.jar");
        Path escaping = drivers.resolve("..").resolve("outside").resolve("postgresql.jar");

        withService(file, drivers, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, escaping.toString(),
                        TestDriverJar.DRIVER_CLASS))));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a symbolic link in the allowed directory that points outside is refused (skipped where links "
            + "cannot be made)")
    void adm060_aSymbolicLinkThatPointsOutsideIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path outside = driverJarIn(realDirectory(directory, "outside"), "postgresql.jar");
        Path link = drivers.resolve("link.jar");
        linkOrSkip(link, outside);

        withService(file, drivers, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, link.toString(),
                        TestDriverJar.DRIVER_CLASS)), outside));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a missing allowed directory refuses every registration with the unified text")
    void adm060_aMissingAllowedDirectoryIsRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path jar = driverJarIn(realDirectory(directory, "elsewhere"), "postgresql.jar");
        Path missing = directory.toRealPath().resolve("no-such-directory");

        withService(file, missing, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, jar.toString(),
                        TestDriverJar.DRIVER_CLASS)), missing));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a jar that does not exist, a directory and a relative path are refused with the same text")
    void adm060_aMissingJarADirectoryAndARelativePathAreRefused(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path missing = drivers.resolve("no-such.jar");
        Path subdirectory = Files.createDirectories(drivers.resolve("a-directory.jar"));

        withService(file, drivers, TestDriverJar.url(), service -> {
            assertRefused(catchThrowable(() -> register(service, missing.toString(),
                    TestDriverJar.DRIVER_CLASS)), missing);
            assertRefused(catchThrowable(() -> register(service, 
                    subdirectory.toString(), TestDriverJar.DRIVER_CLASS)), subdirectory);
            assertRefused(catchThrowable(() -> register(service, "postgresql.jar",
                    TestDriverJar.DRIVER_CLASS)));
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
        Path jar = driverJarIn(drivers, "postgresql.jar");
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(jar), "the process can read the file anyway (a privileged user)");

        withService(file, drivers, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, jar.toString(),
                        TestDriverJar.DRIVER_CLASS)), jar));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: whatever the loader refuses inside the allowed directory has the same text: not an archive, "
            + "class absent, class that is not a driver")
    void adm060_loaderFailuresHaveTheSameText(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");
        Path jar = driverJarIn(drivers, "postgresql.jar");
        Path notAnArchive = OwnerOnlyFiles.write(drivers.resolve("text.jar"),
                    "this is text, not an archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        withService(file, drivers, TestDriverJar.url(), service -> {
            assertRefused(catchThrowable(() -> register(service, 
                    notAnArchive.toString(), TestDriverJar.DRIVER_CLASS)), notAnArchive);
            assertRefused(catchThrowable(() -> register(service, jar.toString(),
                    "org.example.NoSuchDriver")), jar);
            assertRefused(catchThrowable(() -> register(service, jar.toString(),
                    TestDriverJar.NOT_A_DRIVER_CLASS)), jar);
        });
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-060: a blank jar and a blank class keep their own messages (they are what the user typed)")
    void adm060_blankFieldsKeepTheirMessages(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        Path drivers = realDirectory(directory, "drivers");

        withService(file, drivers, TestDriverJar.url(), service -> {
            assertThatThrownBy(() -> register(service, "  ", TestDriverJar.DRIVER_CLASS))
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
        Path jar = driverJarIn(OwnerOnlyFiles.restrict(Files.createDirectories(drivers.resolve("${x}"))), "postgresql.jar");

        withService(file, drivers, TestDriverJar.url(), service -> assertRefused(
                catchThrowable(() -> register(service, jar.toString(),
                        TestDriverJar.DRIVER_CLASS)), jar));
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("ADM-PRN-01: a commit that is refused leaves the registration file byte for byte as it was, no temporary "
            + "file, and the caller gets the failure; with no file before, none exists afterwards")
    void admPrn01_aRefusedCommitLeavesTheFileAlone(@TempDir Path directory) throws Exception {
        Path configuration = realDirectory(directory, "config");
        Path file = configuration.resolve("jdbc-driver.yaml");
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");
        OwnerAuditWriter writer = mock(OwnerAuditWriter.class);
        PlatformTransactionManager refusing = new CommitOf(() -> {
            throw new TransactionSystemException("the commit is refused");
        });

        String before = "the registration that was there before\n";
        Files.writeString(file, before);
        withAuditedService(file, jar.getParent(), writer, refusing, service -> assertThatThrownBy(
                () -> register(service, jar.toString(), TestDriverJar.DRIVER_CLASS))
                .isInstanceOf(TransactionSystemException.class));

        verify(writer).record(eq(TestOwners.OWNER), eq(OwnerAction.DRIVER_REGISTERED), eq(TestDriverJar.DRIVER_CLASS),
                anyString());
        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(names(configuration)).containsExactly("jdbc-driver.yaml");

        Path fresh = configuration.resolve("fresh").resolve("jdbc-driver.yaml");
        withAuditedService(fresh, jar.getParent(), writer, refusing, service -> assertThatThrownBy(
                () -> register(service, jar.toString(), TestDriverJar.DRIVER_CLASS))
                .isInstanceOf(TransactionSystemException.class));
        assertThat(fresh).doesNotExist();
        assertThat(names(fresh.getParent())).isEmpty();
    }

    @Test
    @DisplayName("ADM-PRN-03: the new file is written before the transaction and moved after its commit: at the commit the "
            + "registration file is still the old one, afterwards the new one")
    void admPrn03_theFileIsReplacedAfterTheCommit(@TempDir Path directory) throws Exception {
        Path configuration = realDirectory(directory, "config");
        Path file = configuration.resolve("jdbc-driver.yaml");
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");
        String old = "the registration that was there before\n";
        Files.writeString(file, old);
        List<String> seenAtTheRow = new java.util.ArrayList<>();
        List<String> seenAtTheCommit = new java.util.ArrayList<>();
        OwnerAuditWriter writer = mock(OwnerAuditWriter.class);
        doAnswer(call -> {
            seenAtTheRow.addAll(names(configuration));
            return null;
        }).when(writer).record(any(), any(), any(), any());
        PlatformTransactionManager committing = new CommitOf(() -> {
            try {
                seenAtTheCommit.add(Files.readString(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        withAuditedService(file, jar.getParent(), writer, committing, service -> register(service, jar.toString(),
                TestDriverJar.DRIVER_CLASS));

        assertThat(seenAtTheRow).as("the new file already exists when the audit row is written").hasSize(2)
                .contains("jdbc-driver.yaml");
        assertThat(seenAtTheCommit).containsExactly(old);
        assertThat(Files.readString(file)).contains("registered-at: \"2026-11-05T10:00:00Z\"");
        assertThat(names(configuration)).containsExactly("jdbc-driver.yaml");
    }

    @Test
    @DisplayName("ADM-PRN-02 (service): a replacement that fails after the commit is an error to the caller, the audit row "
            + "was written and committed (it records the attempt), the file is as it was, no temporary file is left")
    void admPrn02_aFailedMoveAfterTheCommitIsAnError(@TempDir Path directory) throws Exception {
        Path configuration = realDirectory(directory, "config");
        // a registration file that cannot be replaced: the name is a directory that is not empty
        Path file = Files.createDirectories(configuration.resolve("jdbc-driver.yaml"));
        Files.writeString(file.resolve("occupied"), "x");
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");
        OwnerAuditWriter writer = mock(OwnerAuditWriter.class);
        List<String> events = new java.util.ArrayList<>();
        PlatformTransactionManager committing = new CommitOf(() -> events.add("commit"));

        withAuditedService(file, jar.getParent(), writer, committing, service -> assertThatThrownBy(
                () -> register(service, jar.toString(), TestDriverJar.DRIVER_CLASS))
                .isInstanceOfSatisfying(InfrastructureException.class,
                        e -> assertThat(e.code()).isEqualTo(InfrastructureCode.INTERNAL_ERROR)));

        verify(writer).record(eq(TestOwners.OWNER), eq(OwnerAction.DRIVER_REGISTERED), eq(TestDriverJar.DRIVER_CLASS),
                anyString());
        assertThat(events).as("the transaction committed before the move failed").containsExactly("commit");
        assertThat(names(configuration)).containsExactly("jdbc-driver.yaml");
        assertThat(names(file)).containsExactly("occupied");
    }

    @Test
    @DisplayName("ADM-PRN-04: in setup mode (no audit writer, no transaction manager) the file is replaced at once")
    void admPrn04_setupModeReplacesTheFileAtOnce(@TempDir Path directory) throws Exception {
        Path configuration = realDirectory(directory, "config");
        Path file = configuration.resolve("jdbc-driver.yaml");
        Path jar = driverJarIn(realDirectory(directory, "drivers"), "postgresql.jar");

        withService(file, jar.getParent(), TestDriverJar.url(), service -> {
            register(service, jar.toString(), TestDriverJar.DRIVER_CLASS);
            assertThat(Files.readString(file)).contains("postgresql.jar").contains(TestDriverJar.DRIVER_CLASS);
        });
        assertThat(names(configuration)).containsExactly("jdbc-driver.yaml");
    }
}
