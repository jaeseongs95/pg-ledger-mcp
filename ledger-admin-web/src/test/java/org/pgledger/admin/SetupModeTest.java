package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.database.driver.DriverRegistration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/**
 * ADM-041, ADM-042, DRV-015, DRV-016, DRV-022: the application without a registered JDBC driver starts in setup mode
 * (no DataSource, no ledger page) and the driver page registers a driver. The test classpath has the H2 driver, which is
 * what the user would supply; it is registered like any other jar.
 */
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX,
    "ledger.jdbc.url=jdbc:h2:mem:setup-mode"})
@AutoConfigureMockMvc
class SetupModeTest {

    private static final Path DIRECTORY = temporaryDirectory();
    private static final Path REGISTRATION = DIRECTORY.resolve("jdbc-driver.yaml");

    @DynamicPropertySource
    static void registrationFile(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.driver-file", () -> REGISTRATION.toString());
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("admin-setup-mode");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path h2Jar() {
        try {
            return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the H2 jar has no path", e);
        }
    }

    @Autowired
    private MockMvc mvc;

    private Optional<String> registrationText() throws IOException {
        return Files.exists(REGISTRATION) ? Optional.of(Files.readString(REGISTRATION)) : Optional.empty();
    }

    @Test
    @DisplayName("ADM-042/DRV-022: in setup mode the login, the health and the driver page work; every other route is refused "
            + "with 503 DRIVER_NOT_CONFIGURED and reads nothing")
    void adm042_setupModeOpensOnlyLoginAndDriverPage() throws Exception {
        Browser anonymous = new Browser(mvc);
        anonymous.perform(anonymous.get("/login")).andExpect(status().isOk());
        anonymous.perform(anonymous.get("/healthz").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        anonymous.perform(anonymous.get("/defects")).andExpect(status().is3xxRedirection());

        Browser owner = Browser.signedIn(mvc);
        owner.perform(owner.get("/driver")).andExpect(status().isOk());
        for (String path : List.of("/", "/defects", "/grants", "/projects", "/history", "/api/overview", "/api/session")) {
            owner.perform(owner.get(path).accept(MediaType.APPLICATION_JSON)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DRIVER_NOT_CONFIGURED"));
        }
        String driverPage = owner.perform(owner.get("/driver")).andReturn().getResponse().getContentAsString();
        assertThat(driverPage).contains("Setup mode").doesNotContain("href=\"/defects\"");
    }

    @Test
    @DisplayName("ADM-042: after the sign-in, setup mode leads to the driver page")
    void adm042_signInLeadsToTheDriverPage() throws Exception {
        Browser browser = new Browser(mvc);

        browser.signIn(AdminTestSettings.TOKEN).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/driver")));
    }

    private record Invalid(String name, String jar, String className) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Invalid> invalidRegistrations() throws IOException {
        Path notAJar = Files.writeString(DIRECTORY.resolve("not-a-jar.jar"), "this is text, not an archive");
        Path empty = Files.write(DIRECTORY.resolve("empty.jar"), new byte[0]);
        String jar = h2Jar().toString();
        return Stream.of(
                new Invalid("path that does not exist", DIRECTORY.resolve("missing.jar").toString(), "org.h2.Driver"),
                new Invalid("a directory", DIRECTORY.toString(), "org.h2.Driver"),
                new Invalid("relative path", "driver.jar", "org.h2.Driver"),
                new Invalid("path with ..", DIRECTORY.resolve("sub").resolve("..").resolve("x.jar").toString(),
                        "org.h2.Driver"),
                new Invalid("text file named .jar", notAJar.toString(), "org.h2.Driver"),
                new Invalid("empty file", empty.toString(), "org.h2.Driver"),
                new Invalid("class absent from the jar", jar, "org.example.NoSuchDriver"),
                new Invalid("class that is not a Driver", jar, "org.h2.engine.Constants"),
                new Invalid("no jar given", "", "org.h2.Driver"),
                new Invalid("no class given", jar, ""));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    @DisplayName("ADM-041/DRV-015: an invalid registration is 400 DRIVER_INVALID, needs the CSRF token and stores nothing")
    void drv015_invalidRegistration(Invalid input) throws Exception {
        Browser owner = Browser.signedIn(mvc);
        Optional<String> before = registrationText();

        owner.perform(owner.form("/driver", Form.of("jar", input.jar(), "className", input.className()), null)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        owner.perform(owner.form("/driver", Form.of("jar", input.jar(), "className", input.className()),
                owner.csrfOf("/driver")).accept(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRIVER_INVALID"));

        assertThat(registrationText()).as("nothing is stored").isEqualTo(before);
    }

    @Test
    @DisplayName("ADM-041/DRV-016: a valid registration is stored with the SHA-256 of the jar and the page says it takes effect "
            + "after the restart; the file is what Spring Boot binds to ledger.jdbc.driver")
    void drv016_validRegistration() throws Exception {
        Browser owner = Browser.signedIn(mvc);
        Path jar = h2Jar();
        MultiValueMap<String, String> fields = Form.of("jar", jar.toString(), "className", "org.h2.Driver");

        owner.perform(owner.form("/driver", fields, owner.csrfOf("/driver"))).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/driver?registered")));

        String page = owner.perform(owner.get("/driver?registered")).andReturn().getResponse().getContentAsString();
        String digest = sha256(jar);
        assertThat(page).contains(jar.toString(), "org.h2.Driver", digest, "takes effect after the next start")
                .contains("restart the products");
        DriverRegistration bound = bind(REGISTRATION);
        assertThat(bound.jar()).isEqualTo(jar);
        assertThat(bound.className()).isEqualTo("org.h2.Driver");
        assertThat(bound.sha256()).isEqualTo(digest);
        assertThat(registrationText().orElseThrow()).contains("registered-at").containsPattern("registered-at: .*Z");
    }

    /** What every product does with the file: Spring Boot loads it as a YAML property source and binds the record. */
    private static DriverRegistration bind(Path file) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("registration", new FileSystemResource(file))
                .forEach(environment.getPropertySources()::addFirst);
        return Binder.get(environment).bind("ledger.jdbc.driver", DriverRegistration.class).get();
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }
}
