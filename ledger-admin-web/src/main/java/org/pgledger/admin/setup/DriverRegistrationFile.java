package org.pgledger.admin.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The registration file of the JDBC driver ({@code jdbc-driver.yaml}): the file that every process of the product reads
 * through {@code spring.config.import}, in the shape of {@code ledger.jdbc.driver.*} (the keys of
 * {@link DriverRegistration}). It holds no secret. It is replaced atomically (a temporary file of the same directory,
 * then a move), and on a file system with POSIX permissions it is readable by the owner and the group only.
 */
public final class DriverRegistrationFile {

    /** The file as the configuration reads it: {@code ledger.jdbc.driver.<key>}. */
    record Document(Ledger ledger) {

        record Ledger(Jdbc jdbc) {
        }

        record Jdbc(Driver driver) {
        }

        record Driver(String jar, String className, String sha256, Instant registeredAt) {
        }
    }

    /** What the file holds. */
    public record Entry(String jar, String className, String sha256, Instant registeredAt) {
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DriverRegistrationFile.class);

    private final YAMLMapper yaml;
    private final Path file;

    public DriverRegistrationFile(YAMLMapper yaml, Path file) {
        this.yaml = yaml;
        this.file = file.toAbsolutePath();
    }

    public Path path() {
        return file;
    }

    /** The registered driver, absent when nothing is registered yet. */
    public Optional<Entry> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Document.Driver driver = yaml.readValue(file, Document.class).ledger().jdbc().driver();
        return Optional.of(new Entry(driver.jar(), driver.className(), driver.sha256(), driver.registeredAt()));
    }

    /** Replaces the registration. */
    public void write(DriverRegistration registration) {
        Document document = new Document(new Document.Ledger(new Document.Jdbc(new Document.Driver(
                registration.jar().toString(), registration.className(), registration.sha256(),
                registration.registeredAt()))));
        Path directory = file.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "..jdbc-driver-", ".tmp");
            yaml.writeValue(temporary, document);
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r-----"));
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            LOG.warn("cannot remove the temporary registration file {}", temporary, e);
        }
    }
}
