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
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.pgledger.database.support.Dbms;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** DRV-002 .. DRV-009, DRV-011, DRV-012, DRV-017 .. DRV-019, DRV-021: the driver boundary and its registration. */
class DriverBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @TempDir
    static Path shared;

    private static Path fakeJar;

    @TempDir
    Path temp;

    @BeforeAll
    static void buildTheFakeJar() throws IOException {
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
    }

    private static Path locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path h2Jar() {
        return locationOf(org.h2.Driver.class);
    }

    private static Path postgresJar() {
        return locationOf(org.postgresql.Driver.class);
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

    private static InfrastructureException describing(String jar, String className, String url) {
        return catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.describe(jar, className, url, NOW));
    }

    static Stream<String> unusableJarPaths() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        return Stream.of("relative.jar", tmp.toString(), tmp.resolve("no-such-driver.jar").toString(),
                tmp.resolve("sub").resolve("..").resolve("x.jar").toString());
    }

    @ParameterizedTest
    @MethodSource("unusableJarPaths")
    void drv002_aPathThatIsNotAUsableJarIsRefused(String path) {
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
        Path file = Files.write(temp.resolve("broken.jar"), broken.content());
        assertThat(codeOf(describing(file.toString(), "org.h2.Driver", "jdbc:h2:mem:x"))).isEqualTo(
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
        InfrastructureException thrown = describing(fakeJar.toString(), problem.className(), "jdbc:fake:x");
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains(problem.reason());
    }

    @Test
    void drv005_aDriverThatDoesNotAcceptTheUrlIsRefusedAndThePasswordIsNotShown() {
        InfrastructureException thrown = describing(h2Jar().toString(), "org.h2.Driver",
                "jdbc:postgresql://localhost/db?user=a&password=top-secret");
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).doesNotContain("top-secret").contains("***");
    }

    private record Target(Dbms dbms, Path jar, String className) {
        @Override
        public String toString() {
            return dbms.name();
        }
    }

    static Stream<Target> targets() {
        return Stream.of(new Target(Dbms.H2, h2Jar(), "org.h2.Driver"),
                new Target(Dbms.POSTGRESQL, postgresJar(), "org.postgresql.Driver"));
    }

    private static Set<String> registeredDrivers() {
        return Collections.list(DriverManager.getDrivers()).stream().map(d -> d.getClass().getName())
                .collect(Collectors.toSet());
    }

    @ParameterizedTest
    @MethodSource("targets")
    void drv006_007_011_theChainIsClassLoaderDriverSimpleDataSourceHikariAndTheDriverManagerIsNotUsed(Target target)
            throws SQLException {
        String url = target.dbms().newUrl();
        Set<String> before = registeredDrivers();
        DriverRegistration registration = DriverLoader.describe(target.jar().toString(), target.className(), url, NOW);
        assertThat(registration).isEqualTo(new DriverRegistration(target.jar().toString(), target.className(),
                sha(target.jar()), NOW));
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
                    new SchemaProperties(null, target.dbms().longText(), true, null), java.time.Clock.systemUTC())
                    .migrate();
            new SchemaVerifier(pool, new PathMatchingResourcePatternResolver(),
                    new SchemaProperties(null, target.dbms().longText(), true, null)).verify();
        }
        assertThat(registeredDrivers()).isEqualTo(before);
    }

    @Test
    void drv008_aJarThatChangedSinceRegistrationIsRefusedBeforeAnyConnection() {
        DriverRegistration registration = new DriverRegistration(h2Jar().toString(), "org.h2.Driver",
                "0".repeat(64), NOW);
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(registration));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains("digest mismatch").contains(h2Jar().toString());
    }

    @Test
    void drv009_aJarThatIsGoneIsRefusedWithItsPath() throws IOException {
        Path copy = Files.copy(h2Jar(), temp.resolve("gone.jar"));
        DriverRegistration registration = DriverLoader.describe(copy.toString(), "org.h2.Driver", "jdbc:h2:mem:g", NOW);
        Files.delete(copy);
        InfrastructureException thrown = catchThrowableOfType(InfrastructureException.class,
                () -> DriverLoader.load(registration));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains(copy.toString());
    }

    static Stream<DriverRegistration> unfinishedRegistrations() {
        return Stream.of(new DriverRegistration(null, null, null, null),
                new DriverRegistration("", "  ", null, null));
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
                () -> DriverLoader.load(new DriverRegistration(h2Jar().toString(), null, null, null)));
        assertThat(thrown.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
        assertThat(thrown.args().getFirst()).contains("ledger.jdbc.driver.class-name");
    }

    @Test
    void drv017_aJarThatOthersCanWriteIsRefused() throws IOException {
        assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Path copy = Files.copy(h2Jar(), temp.resolve("writable.jar"));
        Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-rw-r--"));
        assertThat(codeOf(describing(copy.toString(), "org.h2.Driver", "jdbc:h2:mem:w"))).isEqualTo(
                InfrastructureCode.DRIVER_INVALID);
    }

    @Test
    void drv018_onlyTheNamedClassIsLoadedWhenTwoDriversShareAJar() {
        System.clearProperty(FakeDriverA.MARK);
        System.clearProperty(FakeDriverB.MARK);
        DriverLoader.describe(fakeJar.toString(), FakeDriverA.class.getName(), "jdbc:fake:x", NOW);
        assertThat(System.getProperty(FakeDriverA.MARK)).isEqualTo("yes");
        assertThat(System.getProperty(FakeDriverB.MARK)).isNull();
    }

    @Test
    void drv019_closingReleasesTheJarSoItCanBeDeleted() throws IOException {
        Path copy = Files.copy(h2Jar(), temp.resolve("release.jar"));
        String url = Dbms.H2.newUrl();
        DriverRegistration registration = DriverLoader.describe(copy.toString(), "org.h2.Driver", url, NOW);
        try (RegisteredDriver registered = DriverLoader.load(registration);
                HikariDataSource pool = DriverDataSources.create(registered,
                        new LedgerJdbcProperties(url, null, null, 1, registration), null);
                Connection c = pool.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Files.delete(copy);
        assertThat(copy).doesNotExist();
    }

    /** A registration file and what starting an application from it must do. */
    private record RegistrationFile(String name, String yaml, ErrorCode failure) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<RegistrationFile> registrationFiles() {
        String jar = h2Jar().toString().replace('\\', '/');
        String sha = sha(h2Jar());
        String head = "ledger:\n  jdbc:\n    url: jdbc:h2:mem:cfg\n    driver:\n      jar: \"" + jar + "\"\n";
        return Stream.of(
                new RegistrationFile("complete", head + "      class-name: org.h2.Driver\n      sha256: " + sha
                        + "\n      registered-at: 2026-11-05T10:00:00Z\n", null),
                new RegistrationFile("without digest", head + "      class-name: org.h2.Driver\n",
                        InfrastructureCode.DRIVER_INVALID),
                new RegistrationFile("without class", head + "      sha256: " + sha + "\n",
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
}
