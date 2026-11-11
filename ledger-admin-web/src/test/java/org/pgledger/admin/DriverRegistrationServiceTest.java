package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.admin.setup.DriverRegistrationFile;
import org.pgledger.admin.setup.DriverRegistrationService;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.LedgerJdbcProperties;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** DRV-005 and DRV-020 as far as the admin does them: the URL the driver must accept, and a registration that replaces the file. */
class DriverRegistrationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

    private static Path h2Jar() throws URISyntaxException {
        return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static DriverRegistrationService service(Path file, String url) {
        YAMLMapper yaml = YAMLMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.KEBAB_CASE)
                .disable(tools.jackson.dataformat.yaml.YAMLWriteFeature.SPLIT_LINES)
                .build();
        return new DriverRegistrationService(new DriverRegistrationFile(yaml, file),
                new LedgerJdbcProperties(url, null, null, 1, new DriverRegistration(null, null, null, null)), CLOCK);
    }

    @Test
    @DisplayName("DRV-005: a driver that does not accept the configured JDBC URL is DRIVER_INVALID and nothing is stored")
    void theDriverMustAcceptTheUrl(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");

        assertThatThrownBy(() -> service(file, "jdbc:postgresql://db.example/ledger?password=secret-value")
                .register(h2Jar().toString(), "org.h2.Driver"))
                .isInstanceOfSatisfying(InfrastructureException.class, e -> {
                    assertThat(e.code()).isEqualTo(InfrastructureCode.DRIVER_INVALID);
                    assertThat(e.args().toString()).contains("does not accept").doesNotContain("secret-value");
                });
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("DRV-016/DRV-020: a second registration replaces the first atomically; no temporary file is left")
    void aSecondRegistrationReplacesTheFirst(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jdbc-driver.yaml");
        DriverRegistrationService service = service(file, "jdbc:h2:mem:x");

        service.register(h2Jar().toString(), "org.h2.Driver");
        String first = Files.readString(file);
        DriverRegistration second = service.register(h2Jar().toString(), "org.h2.Driver");

        assertThat(Files.readString(file)).isEqualTo(first).contains("registered-at: \"2026-11-05T10:00:00Z\"");
        assertThat(second.registeredAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        try (var entries = Files.list(directory)) {
            assertThat(entries.toList()).containsExactly(file);
        }
    }

    @Test
    @DisplayName("DRV-016: the state tells that a restart is needed while the registered driver differs from the one in use")
    void restartIsNeededUntilTheDriverInUseIsTheRegisteredOne(@TempDir Path directory) throws URISyntaxException,
            IOException {
        Path file = directory.resolve("jdbc-driver.yaml");
        DriverRegistrationService service = service(file, "jdbc:h2:mem:x");

        assertThat(service.state().registered()).isEmpty();
        assertThat(service.state().restartRequired()).isFalse();
        service.register(h2Jar().toString(), "org.h2.Driver");

        assertThat(service.state().registered()).isPresent();
        assertThat(service.state().restartRequired()).isTrue();
    }
}
