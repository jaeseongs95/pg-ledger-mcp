package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.TestDriverJar;
import org.pgledger.testing.OwnerOnlyFiles;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-PRN-02 (plan 2, F3): a console with its database whose registration file cannot be replaced (the setting names a
 * directory that is not empty, so the atomic move fails after the commit). The registration is an error to the caller
 * (500, the code INTERNAL_ERROR and a trace id), the audit row that was committed stays and records the attempt, and
 * nothing of the new file is left next to the registration file.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class,
    properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.INSECURE_COOKIE,
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX,
    TestDriverJar.URL_PROPERTY})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class DriverRegistrationFailureTest {

    private static final Path DIRECTORY = temporaryDirectory();
    private static final Path DRIVERS = driverDirectory();
    private static final Path REGISTRATION = occupiedRegistration();

    @DynamicPropertySource
    static void registrationFiles(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.driver-file", () -> REGISTRATION.toString());
        registry.add("ledger.admin.driver-directory", () -> DRIVERS.toString());
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("admin-driver-failure").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path driverDirectory() {
        try {
            return OwnerOnlyFiles.restrict(Files.createDirectory(DIRECTORY.resolve("drivers")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The registration "file" is a directory that holds something, so nothing can be moved onto it. */
    private static Path occupiedRegistration() {
        try {
            Path occupied = Files.createDirectories(DIRECTORY.resolve("config").resolve("jdbc-driver.yaml"));
            Files.writeString(occupied.resolve("occupied"), "x");
            return occupied;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("ADM-PRN-02: a registration file that cannot be replaced after the commit is a 500 INTERNAL_ERROR with a "
            + "trace id; the audit row DRIVER_REGISTERED stays (the attempt), the file is as it was, no temporary file is left")
    void aFailedMoveAfterTheCommitIsAnErrorAndTheRowStays() throws Exception {
        Path jar = OwnerOnlyFiles.restrict(Files.copy(TestDriverJar.jar(), DRIVERS.resolve("postgresql.jar"),
                StandardCopyOption.REPLACE_EXISTING)).toRealPath();
        Browser owner = Browser.signedIn(mvc);

        owner.perform(owner.form("/driver", Form.of("jar", jar.toString(), "className", TestDriverJar.DRIVER_CLASS),
                owner.csrfOf("/driver")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").exists());

        assertThat(jdbc.sql("SELECT actor || ' ' || subject FROM owner_audit WHERE operation = 'DRIVER_REGISTERED'")
                .query(String.class).list()).containsExactly("token:owner " + TestDriverJar.DRIVER_CLASS);
        assertThat(REGISTRATION).isDirectory();
        try (var entries = Files.list(REGISTRATION)) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).toList()).containsExactly("occupied");
        }
        try (var entries = Files.list(REGISTRATION.getParent())) {
            assertThat(entries.map(entry -> entry.getFileName().toString()).toList())
                    .isEqualTo(List.of("jdbc-driver.yaml"));
        }
    }
}
