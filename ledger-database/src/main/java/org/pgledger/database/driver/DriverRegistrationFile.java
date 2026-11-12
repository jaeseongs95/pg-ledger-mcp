package org.pgledger.database.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The registration file of the JDBC driver ({@code jdbc-driver.yaml}): the file that every process of the product reads
 * through {@code spring.config.import}, in the shape of {@code ledger.jdbc.driver.*} (the keys of
 * {@link DriverRegistration}). It holds no secret. It is replaced atomically (a temporary file of the same directory,
 * then a move), and on a file system with POSIX permissions it is readable by the owner and the group only.
 *
 * <p>One bean serves the admin web and the command line tool. Where the file is differs by program (each has its own
 * setting), so each program supplies one {@link Location}; the YAML mapper is made here, once, because Spring Boot has no
 * YAML mapper bean (its Jackson auto-configuration makes the JSON one) and both programs read and write the same file.
 *
 * <p>Registering a driver is the audit row of the owner's trail and the replacement of this file. A file cannot be rolled
 * back, so {@link #register} orders the two the way principle 4.4 asks (no file I/O inside a transaction, the follow-up
 * work after the commit): the new file is written next to the old one first, outside any transaction; the audit row is
 * written in a transaction; and the old file is replaced by the new one only after that transaction has committed
 * ({@link TransactionSynchronization#afterCommit}). A transaction that does not commit leaves the old file as it was and
 * removes the new one. A replacement that fails after the commit is logged at ERROR and thrown to the caller (the row
 * stays, it records the attempt), never swallowed.
 */
@Component
public final class DriverRegistrationFile {

    /**
     * Where the file is. A program supplies one, from its own setting ({@code ledger.admin.driver-file},
     * {@code ledger.cli.driver.file}): the key of a setting is the program's business, the file is the same.
     */
    public record Location(Path file) {

        public Location {
            file = Objects.requireNonNull(file, "file").toAbsolutePath();
        }
    }

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

    /** A new file that is written but not yet in place: the temporary file and the file it will replace. */
    private record Pending(Path temporary, Path target) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(DriverRegistrationFile.class);

    /** Kebab-case keys, no line splitting; the one YAML mapper of the registration file. */
    private static final YAMLMapper YAML = YAMLMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.KEBAB_CASE)
            .disable(tools.jackson.dataformat.yaml.YAMLWriteFeature.SPLIT_LINES).build();

    private final Path file;

    public DriverRegistrationFile(Location location) {
        this.file = location.file();
    }

    public Path path() {
        return file;
    }

    /** The registered driver, absent when nothing is registered yet. */
    public Optional<Entry> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Document.Driver driver = YAML.readValue(file, Document.class).ledger().jdbc().driver();
        return Optional.of(new Entry(driver.jar(), driver.className(), driver.sha256(), driver.registeredAt()));
    }

    /**
     * Registers the driver: the new file is written outside any transaction, {@code auditRow} runs inside {@code transaction}
     * (it writes the audit row), and the file is replaced after that transaction has committed. When the commit does not
     * happen (the audit row fails, the commit is refused, the transaction is rolled back) the registration file is not
     * touched and the new file is removed. When the replacement itself fails after the commit, the failure is logged at ERROR
     * and thrown; the audit row stays and records the attempt.
     *
     * <p>Pass {@link TransactionOperations#withoutTransaction()} when there is no database (the admin web in setup mode):
     * there is then no audit row to wait for, and the file is replaced at once.
     *
     * @param transaction the transaction of the audit row; this method must not be called inside a transaction, it opens
     *                    its own (the new file would be written inside it)
     * @param auditRow    writes the audit row; runs in the transaction
     * @throws InfrastructureException INTERNAL_ERROR when the new file cannot be written, or cannot replace the old one
     *         (an atomic move that the file system does not support is a failure too, never a copy)
     * @throws IllegalStateException when called inside a transaction
     */
    public void register(DriverRegistration registration, TransactionOperations transaction, Runnable auditRow) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the driver registration file is written outside a transaction: "
                    + "register opens its own");
        }
        Pending pending = prepare(registration);
        try {
            transaction.executeWithoutResult(status -> {
                auditRow.run();
                replaceAfterCommit(pending);
            });
        } finally {
            // whatever happened, the new file is not left behind: after a commit it has been moved (or removed after a
            // failed move) and this finds nothing; when the audit row failed before the replacement was tied to the
            // transaction, this is what removes it
            discard(pending);
        }
    }

    /**
     * Replaces the registration at once, with no transaction around it (the same temporary file and atomic move as
     * {@link #register}). For a caller that has no audit row to wait for; a caller that has one uses {@link #register}.
     */
    public void write(DriverRegistration registration) {
        replace(prepare(registration));
    }

    /** The new file next to the old one (same directory, so the move is atomic), readable by the owner and the group. */
    private Pending prepare(DriverRegistration registration) {
        Document document = new Document(new Document.Ledger(new Document.Jdbc(new Document.Driver(
                registration.jar().toString(), registration.className(), registration.sha256(),
                registration.registeredAt()))));
        Path directory = file.getParent();
        Path temporary = null;
        boolean prepared = false;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "..jdbc-driver-", ".tmp");
            YAML.writeValue(temporary, document);
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r-----"));
            }
            prepared = true;
            return new Pending(temporary, file);
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        } finally {
            if (!prepared && temporary != null) {
                discard(new Pending(temporary, file));
            }
        }
    }

    /** Ties the replacement to the commit of the running transaction (at once when there is none). */
    private void replaceAfterCommit(Pending pending) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            replace(pending);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                replace(pending);
            }

            @Override
            public void afterCompletion(int status) {
                // after a commit the temporary file has been moved (or removed after a failed move); in every other end
                // of the transaction it is still there and is not wanted
                discard(pending);
            }
        });
    }

    /**
     * The one atomic move. {@link StandardCopyOption#ATOMIC_MOVE} fails (AtomicMoveNotSupportedException) where the file
     * system cannot do it, and that is not hidden: a half-written registration would stop the next start.
     */
    private static void replace(Pending pending) {
        try {
            Files.move(pending.temporary(), pending.target(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // logged here because the caller of a commit sees only that it failed; the same failure goes on to it
            LOG.error("the driver registration file {} could not be replaced; it is as it was (when this follows a "
                    + "committed audit row, the row records the attempt)", pending.target(), e);
            discard(pending);
            throw new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), e);
        }
    }

    private static void discard(Pending pending) {
        try {
            Files.deleteIfExists(pending.temporary());
        } catch (IOException e) {
            LOG.warn("cannot remove the temporary registration file {}", pending.temporary(), e);
        }
    }
}
