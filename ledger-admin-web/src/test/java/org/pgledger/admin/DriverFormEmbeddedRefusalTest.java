package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.TestDriverJar;
import org.pgledger.testing.OwnerOnlyFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SET-033 (the JDBC URL half; the class half is a case of {@link SetupModeTest}): the driver form of the admin web takes its
 * checks from the one check of the products ({@code DriverRegistrar}), so a ledger database whose JDBC URL names the embedded
 * database H2 is refused when a driver is registered for it: 400 DRIVER_INVALID, and no registration file is written (user:
 * 「dbms 설치가 전제라니까?」). The jar and the class are the usable ones, so the URL is the only reason.
 */
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP, AdminTestSettings.QUIET_BANNER,
    AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX, "ledger.jdbc.url=jdbc:h2:mem:not-the-ledger"})
@AutoConfigureMockMvc
class DriverFormEmbeddedRefusalTest {

    private static final Path DIRECTORY = temporaryDirectory();
    private static final Path REGISTRATION = DIRECTORY.resolve("jdbc-driver.yaml");
    private static final Path DRIVERS = driverDirectory();

    @DynamicPropertySource
    static void registrationFile(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.driver-file", () -> REGISTRATION.toString());
        registry.add("ledger.admin.driver-directory", () -> DRIVERS.toString());
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("admin-driver-h2-url").toRealPath();
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

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("SET-033 the driver form refuses a registration while the JDBC URL of the ledger database is an H2 URL: 400 DRIVER_INVALID, no registration file")
    void anH2UrlIsRefused() throws Exception {
        Path jar = OwnerOnlyFiles.restrict(Files.copy(TestDriverJar.jar(), DRIVERS.resolve("postgresql.jar"),
                StandardCopyOption.REPLACE_EXISTING)).toRealPath();
        Browser owner = Browser.signedIn(mvc);

        owner.perform(owner.form("/driver", Form.of("jar", jar.toString(), "className", TestDriverJar.DRIVER_CLASS),
                owner.csrfOf("/driver")).accept(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRIVER_INVALID"));

        assertThat(REGISTRATION).as("nothing is registered").doesNotExist();
    }
}
