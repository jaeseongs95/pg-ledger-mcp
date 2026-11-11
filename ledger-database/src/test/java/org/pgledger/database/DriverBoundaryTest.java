package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.TestAbortedException;
import org.pgledger.database.driver.DriverDataSources;
import org.pgledger.database.driver.DriverLoader;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.LedgerDataSourceConfiguration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.database.driver.RegisteredDriver;
import org.pgledger.database.fake.BaseFakeDriver;
import org.pgledger.database.fake.FakeDriverA;
import org.pgledger.database.fake.FakeDriverB;
import org.pgledger.database.fake.NoDefaultConstructorDriver;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.SchemaProperties;
import org.pgledger.database.schema.SchemaVerifier;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.pgledger.testing.OwnerOnlyFiles;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** DRV-002 .. DRV-009, DRV-011, DRV-012, DRV-017 .. DRV-019, DRV-021, DRV-025 .. DRV-027, DRV-029: the driver boundary and its
 * registration (DRV-028, the owner refusal, is NOT_RUN: it needs a file of another owner). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LedgerDatabaseExtension.class)
class DriverBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private Path fakeJar;

    @TempDir
    Path temp;

    @BeforeAll
    void buildTheFakeJar(@TempDir Path shared) throws IOException {
        OwnerOnlyFiles.restrict(shared);
        fakeJar = shared.resolve("fake-drivers.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(fakeJar))) {
            for (Class<?> type : List.of(BaseFakeDriver.class, FakeDriverA.class, FakeDriverB.class,
                    NoDefaultConstructorDriver.class)) {
                String entry = type.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(entry));
                try (var in = DriverBoundaryTest.class.getClassLoader().getResourceAsStream(entry)) {
                    jar.write(in.readAllBytes());
                }
                jar.closeEntry();
            }
        }
        OwnerOnlyFiles.restrict(fakeJar);
    }

    @BeforeEach
    void trustTheTempDirectory() {
        OwnerOnlyFiles.restrict(temp);
    }

    private static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The H2 jar for the tests: a copy of the one on the class path in a directory of its own that only its owner can use
     * (the loader refuses a jar, or a directory, that other accounts can write, and the Gradle cache is not that on every
     * file system).
     */
    private static final Path H2_JAR = OwnerOnlyFiles.copyToNewDirectory(locationOf(org.h2.Driver.class));

    private static Path h2Jar() {
        return H2_JAR;
    }

    private static String sha(Path file) {
        try {
            return Sha256.ofBytes(Files.readAllBytes(file)).hex();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((InfrastructureException) thrown).code();
    }

    private static InfrastructureException describing(Path jar, String className, String url) {
        return catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.describe(jar, className, url, NOW));
    }

    static Stream<Path> unusableJarPaths() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        return Stream.of(Path.of("relative.jar"), tmp, tmp.resolve("no-such-driver.jar"),
                tmp.resolve("sub").resolve("..").resolve("x.jar"));
    }

    @ParameterizedTest
    @MethodSource("unusableJarPaths")
    void drv002_aPathThatIsNotAUsableJarIsRefused(Path path) {
        assertThat(codeOf(describing(path, "org.h2.Driver", "jdbc:h2:mem:x"))).isEqualTo(
                InfrastructureCode.DRIVER_INVALID);
    }

    /** A file that is called a jar and is not one. */
    private record Broken(String name, byte[] content) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Broken> brokenArchives() throws IOException {
        byte[] real = Files.readAllBytes(h2Jar());
        return Stream.of(new Broken("text file", "this is not an archive".getBytes(StandardCharsets.UTF_8)),
                new Broken("empty file", new byte[0]),
                new Broken("truncated jar", java.util.Arrays.copyOf(real, 100)));
    }

    @ParameterizedTest
    @MethodSource("brokenArchives")
    void drv003_aFileThatIsNotAJarIsRefused(Broken broken) throws IOException {
        Path file = OwnerOnlyFiles.write(temp.resolve("broken.jar"), broken.content());
        assertThat(codeOf(describing(file, "org.h2.Driver", "jdbc:h2:mem:x"))).isEqualTo(
                InfrastructureCode.DRIVER_INVALID);
    }

    /** A class name and the part of the reason that tells it apart. */
    private record ClassProblem(String className, String reason) {
    }

    static Stream<ClassProblem> classProblems() {
        return Stream.of(new ClassProblem("org.pgledger.database.fake.Missing", "is not in"),
                new ClassProblem("java.lang.String", "does not implement java.sql.Driver"),
                new ClassProblem(NoDefaultConstructorDriver.class.getName(), "no public no-argument constructor"));
    }

    @ParameterizedTest
    @MethodSource("classProblems")
    void drv004_aClassThatIsNotAUsableDriverIsRefusedWithItsOwnReason(ClassProblem problem) {
        InfrastructureException thrown = describing(fakeJar, problem.className(), "jdbc:fake:x");
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains(problem.reason());
    }

    @Test
    void drv005_aDriverThatDoesNotAcceptTheUrlIsRefusedAndThePasswordIsNotShown() {
        InfrastructureException thrown = describing(h2Jar(), "org.h2.Driver",
                "jdbc:postgresql://localhost/db?user=a&password=top-secret");
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).doesNotContain("top-secret").contains("***");
    }

    private static Set<String> registeredDrivers() {
        return Collections.list(DriverManager.getDrivers()).stream().map(d -> d.getClass().getName())
                .collect(Collectors.toSet());
    }

    @Test
    void drv006_007_011_theChainIsClassLoaderDriverSimpleDataSourceHikariAndTheDriverManagerIsNotUsed(
            Databases databases) throws SQLException {
        String url = databases.newUrl();
        Path jar = h2Jar();
        Set<String> before = registeredDrivers();
        DriverRegistration registration = DriverLoader.describe(jar, "org.h2.Driver", url, NOW);
        assertThat(registration).isEqualTo(new DriverRegistration(jar, "org.h2.Driver", sha(jar), NOW));
        LedgerJdbcProperties properties = new LedgerJdbcProperties(url, null, null, 2, registration);
        try (RegisteredDriver registered = DriverLoader.load(registration);
                HikariDataSource pool = DriverDataSources.create(registered, properties, null)) {
            Driver driver = registered.driver();
            assertThat(registeredDrivers()).as("the DriverManager is not involved while the pool runs").isEqualTo(before);
            assertThat(driver.getClass().getClassLoader()).isInstanceOf(URLClassLoader.class);
            assertThat(pool.getDataSource()).isInstanceOf(SimpleDriverDataSource.class);
            try (Connection c = pool.getConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT 1")) {
                assertThat(rs.next()).isTrue();
            }
            new SchemaMigrator(pool, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                            java.time.Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT)),
                    java.time.Clock.systemUTC())
                    .migrate();
            new SchemaVerifier(pool, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(SchemaProperties.DEFAULT_LOCATION, Databases.LONG_TEXT, true, null,
                            java.time.Duration.parse(SchemaProperties.DEFAULT_PEER_WAIT))).verify();
        }
        assertThat(registeredDrivers()).isEqualTo(before);
    }

    @Test
    void drv008_aJarThatChangedSinceRegistrationIsRefusedBeforeAnyConnection() {
        DriverRegistration registration = new DriverRegistration(h2Jar(), "org.h2.Driver",
                "0".repeat(64), NOW);
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(registration));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains("digest mismatch").contains(h2Jar().toString());
    }

    @Test
    void drv009_aJarThatIsGoneIsRefusedWithItsPath() throws IOException {
        Path copy = OwnerOnlyFiles.copy(h2Jar(), temp.resolve("gone.jar"));
        DriverRegistration registration = DriverLoader.describe(copy, "org.h2.Driver", "jdbc:h2:mem:g", NOW);
        Files.delete(copy);
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(registration));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains(copy.toString());
    }

    static Stream<DriverRegistration> unfinishedRegistrations() {
        return Stream.of(new DriverRegistration(null, null, null, null),
                new DriverRegistration(null, "  ", null, null));
    }

    @ParameterizedTest
    @MethodSource("unfinishedRegistrations")
    void drv012_withoutARegistrationTheStartFailsFastAndNamesTheCommand(DriverRegistration none) {
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(none));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_NOT_CONFIGURED);
        assertThat(thrown.getMessage()).contains("driver-register");
    }

    @Test
    void drv012_aHalfFilledRegistrationNamesTheMissingKey() {
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(new DriverRegistration(h2Jar(), null, null, null)));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains("ledger.jdbc.driver.class-name");
    }

    @Test
    void drv017_aJarThatOthersCanWriteIsRefused() throws IOException {
        assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Path copy = OwnerOnlyFiles.copy(h2Jar(), temp.resolve("writable.jar"));
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-rw-r--"));
        assertThat(codeOf(describing(copy, "org.h2.Driver", "jdbc:h2:mem:w"))).isEqualTo(
                InfrastructureCode.DRIVER_INVALID);
    }


    private static boolean hasAcl(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class) != null;
    }

    private static boolean hasPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    /** DRV-030 (audit2 N4): a directory that the group or others can write is refused, the jar in it being fine. */
    @Test
    void drv030_aDirectoryThatOthersCanWriteIsRefused() throws IOException {
        assumeTrue(hasPosix(temp), "needs POSIX permissions");
        Path directory = OwnerOnlyFiles.restrict(Files.createDirectory(temp.resolve("drivers-open")));
        Path jar = OwnerOnlyFiles.copy(h2Jar(), directory.resolve("h2.jar"));
        DriverLoader.describe(jar, "org.h2.Driver", "jdbc:h2:mem:dir-ok", NOW);

        for (String mode : List.of("rwxrwx---", "rwx---rwx", "rwx-w----", "rwx----w-")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(mode));
            InfrastructureException refused = describing(jar, "org.h2.Driver", "jdbc:h2:mem:dir");
            assertThat(codeOf(refused)).as(mode).isEqualTo(InfrastructureCode.DRIVER_INVALID);
            assertThat(refused.args().getFirst()).as(mode).contains("directory").contains("writable");
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        DriverLoader.describe(jar, "org.h2.Driver", "jdbc:h2:mem:dir-again", NOW);
    }

    /**
     * DRV-031 (audit2 N4): with ACLs, an entry that lets another account change the jar or its directory refuses it; an
     * entry that only lets it read does not.
     */
    @Test
    void drv031_aJarOrDirectoryThatAnotherAccountCanWriteIsRefusedByAcl() throws IOException {
        assumeTrue(hasAcl(temp) && !hasPosix(temp), "needs ACLs");
        UserPrincipal everyone = temp.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
        for (boolean onDirectory : new boolean[] {true, false}) {
            Path directory = OwnerOnlyFiles.restrict(Files.createDirectory(temp.resolve("acl-" + onDirectory)));
            Path jar = OwnerOnlyFiles.copy(h2Jar(), directory.resolve("h2.jar"));
            Path target = onDirectory ? directory : jar;
            AclFileAttributeView view = Files.getFileAttributeView(target, AclFileAttributeView.class);
            List<AclEntry> original = view.getAcl();

            view.setAcl(withEntry(original, everyone, AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));
            DriverLoader.describe(jar, "org.h2.Driver", "jdbc:h2:mem:acl-read-" + onDirectory, NOW);

            for (AclEntryPermission change : List.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)) {
                view.setAcl(withEntry(original, everyone, AclEntryPermission.READ_DATA, change));
                InfrastructureException refused = describing(jar, "org.h2.Driver", "jdbc:h2:mem:acl");
                assertThat(codeOf(refused)).as("%s on the %s", change, onDirectory ? "directory" : "jar")
                        .isEqualTo(InfrastructureCode.DRIVER_INVALID);
                assertThat(refused.args().getFirst()).contains(onDirectory ? "directory" : "jar").contains("can be changed");
            }
            view.setAcl(original);
            DriverLoader.describe(jar, "org.h2.Driver", "jdbc:h2:mem:acl-again-" + onDirectory, NOW);
        }
    }

    private static List<AclEntry> withEntry(List<AclEntry> original, UserPrincipal who, AclEntryPermission... permissions) {
        List<AclEntry> entries = new ArrayList<>(original);
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(EnumSet.copyOf(List.of(permissions))).build());
        return entries;
    }

    /** DRV-032 (audit2 N4, S3): a directory whose ACL has no entry at all is refused. */
    @Test
    void drv032_aDirectoryWithAnEmptyAclIsRefused() throws IOException {
        assumeTrue(hasAcl(temp) && !hasPosix(temp), "needs ACLs");
        Path directory = OwnerOnlyFiles.restrict(Files.createDirectory(temp.resolve("acl-empty")));
        Path jar = OwnerOnlyFiles.copy(h2Jar(), directory.resolve("h2.jar"));
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        List<AclEntry> original = view.getAcl();
        view.setAcl(List.of());
        try {
            InfrastructureException refused = describing(jar, "org.h2.Driver", "jdbc:h2:mem:acl-empty");
            assertThat(codeOf(refused)).isEqualTo(InfrastructureCode.DRIVER_INVALID);
            assertThat(refused.args().getFirst()).contains("without any entry");
        } finally {
            view.setAcl(original);
        }
    }

    /** DRV-033 (audit2 N4): the jar is opened without following a link: a link to a jar is refused. */
    @Test
    void drv033_aSymbolicLinkToAJarIsRefused() throws IOException {
        Path link = temp.resolve("link.jar");
        try {
            Files.createSymbolicLink(link, h2Jar());
        } catch (UnsupportedOperationException | IOException cannot) {
            throw new TestAbortedException("this file system cannot make symbolic links here: " + cannot, cannot);
        }
        assertThat(codeOf(describing(link, "org.h2.Driver", "jdbc:h2:mem:link"))).isEqualTo(InfrastructureCode.DRIVER_INVALID);
    }

    @Test
    void drv018_onlyTheNamedClassIsLoadedWhenTwoDriversShareAJar() {
        System.clearProperty(FakeDriverA.MARK);
        System.clearProperty(FakeDriverB.MARK);
        DriverLoader.describe(fakeJar, FakeDriverA.class.getName(), "jdbc:fake:x", NOW);
        assertThat(System.getProperty(FakeDriverA.MARK)).isEqualTo("yes");
        assertThat(System.getProperty(FakeDriverB.MARK)).isNull();
    }

    @Test
    void drv019_closingReleasesTheJarSoItCanBeDeleted(Databases databases) throws IOException, SQLException {
        Path copy = OwnerOnlyFiles.copy(h2Jar(), temp.resolve("release.jar"));
        String url = databases.newUrl();
        DriverRegistration registration = DriverLoader.describe(copy, "org.h2.Driver", url, NOW);
        try (RegisteredDriver registered = DriverLoader.load(registration);
                HikariDataSource pool = DriverDataSources.create(registered,
                        new LedgerJdbcProperties(url, null, null, 1, registration), null);
                Connection c = pool.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }
        Files.delete(copy);
        assertThat(copy).doesNotExist();
    }

    private static final Path TEMPORARY_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"));

    /** The files of the temporary-file directory that look like a jar: the private copy of the loader is one of them. */
    private static Set<Path> temporaryJars() throws IOException {
        try (Stream<Path> files = Files.list(TEMPORARY_DIRECTORY)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jar")).collect(Collectors.toSet());
        }
    }

    @Test
    void drv025_theClassesComeFromAPrivateCopyOfTheBytesThatWereHashed() throws Exception {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("swapped-after-load.jar"));
        DriverRegistration registration = DriverLoader.describe(registered, FakeDriverA.class.getName(), "jdbc:fake:x",
                NOW);
        try (RegisteredDriver driver = DriverLoader.load(registration)) {
            Path source = Path.of(driver.driver().getClass().getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            assertThat(Files.isSameFile(source, registered)).as("the classes are not read from the registered file")
                    .isFalse();
            Files.write(registered, Files.readAllBytes(h2Jar()));
            Class<?> notLoadedYet = driver.driver().getClass().getClassLoader().loadClass(FakeDriverB.class.getName());
            assertThat(notLoadedYet.getName()).as("still the old bytes after the file was overwritten")
                    .isEqualTo(FakeDriverB.class.getName());
        }
    }

    @Test
    void drv026_theRegisteredFileCanBeDeletedWhileTheDriverIsOpen() throws Exception {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("deleted-while-open.jar"));
        DriverRegistration registration = DriverLoader.describe(registered, FakeDriverA.class.getName(), "jdbc:fake:x",
                NOW);
        try (RegisteredDriver driver = DriverLoader.load(registration)) {
            Files.delete(registered);
            assertThat(registered).doesNotExist();
            assertThat(driver.driver().acceptsURL("jdbc:fake:y")).isTrue();
            assertThat(driver.driver().getClass().getClassLoader().loadClass(FakeDriverB.class.getName()))
                    .isNotNull();
        }
    }

    @Test
    void drv027_thePrivateCopyExistsWhileTheDriverIsOpenAndIsGoneAfterwards() throws Exception {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("lifecycle.jar"));
        Set<Path> before = temporaryJars();

        DriverRegistration registration = DriverLoader.describe(registered, FakeDriverA.class.getName(), "jdbc:fake:x",
                NOW);
        assertThat(temporaryJars()).as("describe closes its driver and leaves no copy").isEqualTo(before);

        try (RegisteredDriver driver = DriverLoader.load(registration)) {
            Set<Path> added = temporaryJars().stream().filter(file -> !before.contains(file))
                    .collect(Collectors.toSet());
            assertThat(added).as("one private copy while the driver is open").hasSize(1);
            if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(added.iterator().next()))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
            assertThat(driver.driver()).isNotNull();
        }
        assertThat(temporaryJars()).as("closing the driver removes the copy").isEqualTo(before);
    }

    @Test
    void drv027_aRefusedLoadLeavesNoCopy() throws IOException {
        Path registered = OwnerOnlyFiles.copy(fakeJar, temp.resolve("refused.jar"));
        Path notAnArchive = OwnerOnlyFiles.write(temp.resolve("refused-text.jar"),
                "this is not an archive".getBytes(StandardCharsets.UTF_8));
        Set<Path> before = temporaryJars();

        assertThat(codeOf(catchThrowableOfType(InfrastructureException.class, () -> DriverLoader.load(
                new DriverRegistration(registered, FakeDriverA.class.getName(), "0".repeat(64), NOW)))))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(codeOf(catchThrowableOfType(InfrastructureException.class, () -> DriverLoader.load(
                new DriverRegistration(notAnArchive, FakeDriverA.class.getName(), sha(notAnArchive), NOW)))))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(codeOf(catchThrowableOfType(InfrastructureException.class, () -> DriverLoader.load(
                new DriverRegistration(registered, "org.pgledger.database.fake.Missing", sha(registered), NOW)))))
                .isEqualTo(InfrastructureCode.DRIVER_INVALID);

        assertThat(temporaryJars()).isEqualTo(before);
    }

    @Test
    void drv029_aJarPathWithAPlaceholderIsRefusedByTheLoader() throws IOException {
        Path directory = Files.createDirectory(temp.resolve("${x}"));
        Path jar = OwnerOnlyFiles.copy(h2Jar(), directory.resolve("h2.jar"));

        InfrastructureException described = describing(jar, "org.h2.Driver", "jdbc:h2:mem:p");
        InfrastructureException loaded = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(new DriverRegistration(jar, "org.h2.Driver", sha(jar), NOW)));

        assertThat(described).as("describe refuses a path with ${").isNotNull();
        assertThat(codeOf(described)).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(described.args().getFirst()).contains("${");
        assertThat(loaded).as("load refuses a path with ${").isNotNull();
        assertThat(codeOf(loaded)).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(loaded.args().getFirst()).contains("${");
    }

    /** A registration file and what starting an application from it must do. */
    private record RegistrationFile(String name, String yaml, ErrorCode failure) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<RegistrationFile> registrationFiles() {
        String jar = h2Jar().toString();
        String sha = sha(h2Jar());
        // A YAML single-quoted scalar keeps the path exactly as the file system writes it.
        String head = "ledger:\n  jdbc:\n    url: jdbc:h2:mem:cfg\n    driver:\n      jar: '" + jar + "'\n";
        return Stream.of(
                new RegistrationFile("complete", head + "      class-name: org.h2.Driver\n      sha256: " + sha
                        + "\n      registered-at: 2026-11-05T10:00:00Z\n", null),
                new RegistrationFile("without digest", head + "      class-name: org.h2.Driver\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("without class", head + "      sha256: " + sha + "\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("relative jar path", "ledger:\n  jdbc:\n    url: jdbc:h2:mem:cfg\n    driver:\n"
                        + "      jar: relative/driver.jar\n      class-name: org.h2.Driver\n      sha256: " + sha + "\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("nothing registered", "ledger:\n  jdbc:\n    url: jdbc:h2:mem:cfg\n",
                        InfrastructureCode.DRIVER_NOT_CONFIGURED));
    }

    @ParameterizedTest
    @MethodSource("registrationFiles")
    void drv021_theRegistrationFileIsReadThroughConfigImportAndAnIncompleteOneFailsFast(RegistrationFile file)
            throws IOException {
        Path yaml = Files.writeString(temp.resolve("jdbc-driver.yaml"), file.yaml());
        Throwable failure = catchThrowableOfType(Throwable.class, () -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    LedgerDataSourceConfiguration.class).web(WebApplicationType.NONE)
                    .properties("spring.config.import=file:" + yaml.toUri().getPath(), "spring.main.banner-mode=off",
                            "logging.level.root=WARN").run()) {
                DataSource dataSource = context.getBean(DataSource.class);
                try (Connection c = dataSource.getConnection()) {
                    assertThat(c.isValid(2)).isTrue();
                }
            }
        });
        assertThat(failure == null ? null : codeOf(NestedExceptionUtils.getMostSpecificCause(failure)))
                .isEqualTo(file.failure());
    }

    @Test
    void drv024_aJarValueThatIsNotAPathStopsTheStartWithTheBindingFailureNamingTheKey() throws IOException {
        Path yaml = Files.writeString(temp.resolve("jdbc-driver-bad-path.yaml"),
                "ledger:\n  jdbc:\n    url: jdbc:h2:mem:cfg\n    driver:\n      jar: \"bad\\0path.jar\"\n"
                        + "      class-name: org.h2.Driver\n");
        Throwable failure = catchThrowableOfType(Throwable.class, () -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    LedgerDataSourceConfiguration.class).web(WebApplicationType.NONE)
                    .properties("spring.config.import=file:" + yaml.toUri().getPath(), "spring.main.banner-mode=off",
                            "logging.level.root=WARN").run()) {
                assertThat(context.isRunning()).isFalse();
            }
        });
        assertThat(failure).isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof BindException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(BindException.class);
        assertThat(((BindException) cause).getProperty().getName().toString()).isEqualTo("ledger.jdbc.driver.jar");
    }
}
