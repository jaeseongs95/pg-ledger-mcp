package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.TestDriverJar;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.testing.OwnerOnlyFiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * (no DataSource, no ledger page) and the driver page registers a driver. The PostgreSQL driver jar of the test build
 * is what the user would supply; it is registered like any other jar (the registration only asks the driver whether it
 * accepts the URL and never connects).
 */
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, AdminTestSettings.NO_GLOBAL_CAP,
    AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG, AdminTestSettings.NO_JMX,
    TestDriverJar.URL_PROPERTY})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SetupModeTest {

    private static final Path DIRECTORY = temporaryDirectory();
    private static final Path REGISTRATION = DIRECTORY.resolve("jdbc-driver.yaml");

    /** The one allowed directory of the driver jars (ADM-060): the jars of these tests are copied into it. */
    private static final Path DRIVERS = driverDirectory();

    @DynamicPropertySource
    static void registrationFile(DynamicPropertyRegistry registry) {
        registry.add("ledger.admin.driver-file", () -> REGISTRATION.toString());
        registry.add("ledger.admin.driver-directory", () -> DRIVERS.toString());
    }

    /** The real path: the registered path is a real path (symbolic links resolved), and the tests compare with it. */
    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("admin-setup-mode").toRealPath();
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

    /** The PostgreSQL driver jar where the build resolved it: outside the allowed directory. */
    private static Path outsideDriverJar() {
        return TestDriverJar.jar();
    }

    /** A copy of the PostgreSQL driver jar inside the allowed directory: the jar the user would put there. */
    private static Path driverJar() {
        try {
            return OwnerOnlyFiles.restrict(Files.copy(outsideDriverJar(), DRIVERS.resolve("postgresql.jar"),
                    StandardCopyOption.REPLACE_EXISTING)).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        Path notAJar = OwnerOnlyFiles.write(DRIVERS.resolve("not-a-jar.jar"), "this is text, not an archive".getBytes(StandardCharsets.UTF_8));
        Path empty = OwnerOnlyFiles.write(DRIVERS.resolve("empty.jar"), new byte[0]);
        String jar = driverJar().toString();
        return Stream.of(
                new Invalid("path that does not exist", DRIVERS.resolve("missing.jar").toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("a directory", DRIVERS.toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("relative path", "driver.jar", TestDriverJar.DRIVER_CLASS),
                new Invalid("path with ..", DRIVERS.resolve("sub").resolve("..").resolve("x.jar").toString(),
                        TestDriverJar.DRIVER_CLASS),
                new Invalid("text file named .jar", notAJar.toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("empty file", empty.toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("class absent from the jar", jar, "org.example.NoSuchDriver"),
                new Invalid("class that is not a Driver", jar, TestDriverJar.NOT_A_DRIVER_CLASS),
                // SET-033: the form takes its checks from DriverRegistrar.check, so the refusal of the embedded database holds
                new Invalid("the driver class of H2 (SET-033)", jar, "org.h2.Driver"),
                new Invalid("a valid jar outside the allowed directory", outsideDriverJar().toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("no jar given", "", TestDriverJar.DRIVER_CLASS),
                new Invalid("no class given", jar, ""));
    }

    @Test
    @DisplayName("ADM-063: the failure of a refused jar or class tells the client nothing about the file: the same text for "
            + "every cause, no path in it (JSON client and browser page), the specific reason only in the server log")
    void adm063_theClientLearnsNothingAboutTheJar(CapturedOutput output) throws Exception {
        Browser owner = Browser.signedIn(mvc);
        Path notAJar = OwnerOnlyFiles.write(DRIVERS.resolve("not-a-jar-63.jar"), "this is text, not an archive".getBytes(StandardCharsets.UTF_8));
        String inside = driverJar().toString();
        List<Invalid> refused = List.of(
                new Invalid("missing", DRIVERS.resolve("missing-63.jar").toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("outside", outsideDriverJar().toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("not an archive", notAJar.toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("class absent", inside, "org.example.NoSuchDriver63"),
                new Invalid("directory", DRIVERS.toString(), TestDriverJar.DRIVER_CLASS),
                new Invalid("relative", "driver-63.jar", TestDriverJar.DRIVER_CLASS));
        Set<String> details = new HashSet<>();

        for (Invalid input : refused) {
            String json = owner.perform(owner.form("/driver", Form.of("jar", input.jar(), "className",
                    input.className()), owner.csrfOf("/driver")).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRIVER_INVALID"))
                    .andReturn().getResponse().getContentAsString();
            String detail = JsonPath.read(json, "$.detail");
            details.add(detail);
            assertThat(detail).as(input.name()).contains("the jar or the class cannot be used for a driver")
                    .doesNotContain(input.jar()).doesNotContain(DRIVERS.toString()).doesNotContain("org.example");

            String page = owner.perform(owner.form("/driver", Form.of("jar", input.jar(), "className",
                    input.className()), owner.csrfOf("/driver"))).andExpect(status().isBadRequest()).andReturn()
                    .getResponse().getContentAsString();
            assertThat(page).as(input.name() + " (page)").contains("the jar or the class cannot be used for a driver")
                    .doesNotContain(input.jar()).doesNotContain(DRIVERS.toString()).doesNotContain("org.example");
        }

        assertThat(details).as("the same text for every cause").hasSize(1);
        assertThat(output.getAll()).as("the specific reason is in the server log").contains("missing-63.jar");
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
    void drv016_validRegistration(CapturedOutput output) throws Exception {
        Browser owner = Browser.signedIn(mvc);
        Path jar = driverJar();
        MultiValueMap<String, String> fields = Form.of("jar", jar.toString(), "className", TestDriverJar.DRIVER_CLASS);

        owner.perform(owner.form("/driver", fields, owner.csrfOf("/driver"))).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/driver?registered")));

        String page = owner.perform(owner.get("/driver?registered")).andReturn().getResponse().getContentAsString();
        String digest = sha256(jar);
        assertThat(page).contains(jar.toString(), TestDriverJar.DRIVER_CLASS, digest, "takes effect after the next start")
                .contains("restart the products");
        DriverRegistration bound = bind(REGISTRATION);
        assertThat(bound.jar()).isEqualTo(jar);
        assertThat(bound.className()).isEqualTo(TestDriverJar.DRIVER_CLASS);
        assertThat(bound.sha256()).isEqualTo(digest);
        assertThat(registrationText().orElseThrow()).contains("registered-at").containsPattern("registered-at: .*Z");
        // ADM-072 (audit2 N3): there is no database in setup mode, so no audit row; the registration is logged with the owner
        assertThat(output.getAll()).contains("driver registered by token:owner").contains(TestDriverJar.DRIVER_CLASS).contains(digest);
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
