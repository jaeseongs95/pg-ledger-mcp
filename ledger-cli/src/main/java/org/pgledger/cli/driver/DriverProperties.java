package org.pgledger.cli.driver;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where {@code driver-register} writes the registration and where it accepts a jar from; both values (and their defaults,
 * which follow {@code ledger.secrets.instance}) come from application.properties and can be overridden. Checking a jar runs
 * code of it, so a jar is taken from the one directory only; the file is the one every process of the product reads through
 * {@code spring.config.import}.
 *
 * @param file      the registration file ({@code jdbc-driver.yaml})
 * @param directory the one directory a driver jar must be in
 */
@Validated
@ConfigurationProperties("ledger.cli.driver")
public record DriverProperties(@NotNull Path file, @NotNull Path directory) {
}
