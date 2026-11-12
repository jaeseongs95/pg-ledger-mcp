package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.database.driver.DriverRegistration;
import org.pgledger.database.driver.DriverRegistrationFile;
import org.pgledger.database.driver.DriverRegistrationFile.Location;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DRV-040 .. DRV-045 (F3, principle 4.4): the registration file is replaced after the audit row has committed, never
 * inside its transaction. The audit row is a real row of {@code owner_audit} in the PostgreSQL of the test, the transaction a real
 * Spring transaction on it; only the commit can be made to fail.
 */
@DatabaseTest
class DriverRegistrationFileTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final DriverRegistration OLD = new DriverRegistration(Path.of("/drivers/old.jar"), "org.old.Driver",
            "a".repeat(64), Instant.parse("2026-11-05T10:00:00Z"));
    private static final DriverRegistration NEW = new DriverRegistration(Path.of("/drivers/new.jar"), "org.new.Driver",
            "b".repeat(64), Instant.parse("2026-11-06T10:00:00Z"));

    @TempDir
    Path directory;

    /** A transaction manager on the PostgreSQL of the test whose commit can be refused (the work is rolled back then). */
    private static final class CommitCanFail extends DataSourceTransactionManager {
        private static final long serialVersionUID = 1L;
        private final boolean refuse;

        CommitCanFail(DataSource dataSource, boolean refuse) {
            super(dataSource);
            this.refuse = refuse;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (refuse) {
                doRollback(status);
                throw new TransactionSystemException("the commit is refused");
            }
            super.doCommit(status);
        }
    }

    private static TransactionTemplate transaction(LedgerTestContext ctx, boolean refuseCommit) {
        return new TransactionTemplate(new CommitCanFail(ctx.pool(), refuseCommit));
    }

    private static void auditRow(LedgerTestContext ctx, long id) {
        JdbcClient.create(ctx.pool()).sql("INSERT INTO owner_audit (id, occurred_at, actor, operation, subject) "
                + "VALUES (:id, :at, 'token:owner', 'DRIVER_REGISTERED', 'x')").param("id", id).param("at", WHEN)
                .update();
    }

    private static long auditRows(LedgerTestContext ctx, long id) {
        return JdbcClient.create(ctx.pool()).sql("SELECT COUNT(*) FROM owner_audit WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }

    /** A file with the old registration in it, in a directory that does not exist yet. */
    private DriverRegistrationFile fileWithTheOldRegistration() {
        DriverRegistrationFile file = new DriverRegistrationFile(new Location(directory.resolve("conf/jdbc-driver.yaml")));
        file.write(OLD);
        return file;
    }

    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("DRV-040 a committed transaction replaces the file after the commit: inside it the old file is still there, afterwards the new one, and no temporary file stays")
    void theFileIsReplacedAfterTheCommit(LedgerTestContext ctx) throws IOException {
        DriverRegistrationFile file = fileWithTheOldRegistration();
        List<String> seenInsideTheTransaction = new ArrayList<>();
        List<String> seenJustBeforeTheCommit = new ArrayList<>();

        file.register(NEW, transaction(ctx, false), () -> {
            auditRow(ctx, 8_100_001L);
            seenInsideTheTransaction.add(file.read().orElseThrow().sha256());
            // looks again at the last moment before the commit, after the replacement has been tied to the transaction
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    seenJustBeforeTheCommit.add(file.read().orElseThrow().sha256());
                }
            });
        });

        assertThat(seenInsideTheTransaction).as("not replaced while the audit row is not committed")
                .containsExactly(OLD.sha256());
        assertThat(seenJustBeforeTheCommit).as("still not replaced just before the commit").containsExactly(OLD.sha256());
        assertThat(file.read().orElseThrow()).isEqualTo(new DriverRegistrationFile.Entry(NEW.jar().toString(),
                NEW.className(), NEW.sha256(), NEW.registeredAt()));
        assertThat(auditRows(ctx, 8_100_001L)).isEqualTo(1);
        assertThat(namesIn(file.path().getParent())).containsExactly("jdbc-driver.yaml");
    }

    @Test
    @DisplayName("DRV-041 a commit that fails leaves the file as it was and removes the temporary file; the failure reaches the caller")
    void aFailedCommitLeavesTheFile(LedgerTestContext ctx) throws IOException {
        DriverRegistrationFile file = fileWithTheOldRegistration();

        assertThatThrownBy(() -> file.register(NEW, transaction(ctx, true), () -> auditRow(ctx, 8_100_002L)))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(file.read().orElseThrow().sha256()).isEqualTo(OLD.sha256());
        assertThat(namesIn(file.path().getParent())).containsExactly("jdbc-driver.yaml");
        assertThat(auditRows(ctx, 8_100_002L)).as("the row did not commit").isZero();
    }

    @Test
    @DisplayName("DRV-042 an audit row that cannot be written leaves the file as it was and removes the temporary file")
    void aFailedAuditRowLeavesTheFile(LedgerTestContext ctx) throws IOException {
        DriverRegistrationFile file = fileWithTheOldRegistration();

        assertThatThrownBy(() -> file.register(NEW, transaction(ctx, false), () -> {
            throw new IllegalStateException("the audit row is refused");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the audit row is refused");

        assertThat(file.read().orElseThrow().sha256()).isEqualTo(OLD.sha256());
        assertThat(namesIn(file.path().getParent())).containsExactly("jdbc-driver.yaml");
    }

    @Test
    @DisplayName("DRV-043 a move that fails after the commit is logged at ERROR and thrown (the row stays), and no temporary file is left")
    void aFailedMoveIsNotSwallowed(LedgerTestContext ctx) throws IOException {
        // the target is a directory that is not empty: no file system moves a file over it
        Path target = Files.createDirectories(directory.resolve("not-a-file/inside"));
        Path blocked = target.getParent();
        DriverRegistrationFile file = new DriverRegistrationFile(new Location(blocked));
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(DriverRegistrationFile.class);
        logger.addAppender(log);
        try {
            assertThatThrownBy(() -> file.register(NEW, transaction(ctx, false), () -> auditRow(ctx, 8_100_003L)))
                    .isInstanceOfSatisfying(InfrastructureException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(InfrastructureCode.INTERNAL_ERROR);
                        assertThat(failure.getCause()).isInstanceOf(IOException.class);
                    });
        } finally {
            logger.detachAppender(log);
        }

        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
        assertThat(auditRows(ctx, 8_100_003L)).as("the audit row committed first and records the attempt").isEqualTo(1);
        assertThat(Files.isDirectory(blocked)).isTrue();
        assertThat(namesIn(directory)).as("no temporary file next to the target").containsExactly("not-a-file");
    }

    @Test
    @DisplayName("DRV-044 register opens its own transaction: called inside one it refuses, because the new file would be written inside it")
    void itIsRefusedInsideATransaction(LedgerTestContext ctx) throws IOException {
        DriverRegistrationFile file = fileWithTheOldRegistration();

        transaction(ctx, false).executeWithoutResult(status -> assertThatThrownBy(
                () -> file.register(NEW, TransactionOperations.withoutTransaction(), () -> {
                })).isInstanceOf(IllegalStateException.class));

        assertThat(file.read().orElseThrow().sha256()).isEqualTo(OLD.sha256());
        assertThat(namesIn(file.path().getParent())).containsExactly("jdbc-driver.yaml");
    }

    @Test
    @DisplayName("DRV-045 without a database there is no audit row to wait for: the file is replaced at once")
    void withoutATransactionTheFileIsReplacedAtOnce() throws IOException {
        DriverRegistrationFile file = fileWithTheOldRegistration();
        List<String> order = new ArrayList<>();

        file.register(NEW, TransactionOperations.withoutTransaction(), () -> order.add(file.read().orElseThrow().sha256()));

        assertThat(order).containsExactly(OLD.sha256());
        assertThat(file.read().orElseThrow().sha256()).isEqualTo(NEW.sha256());
        assertThat(namesIn(file.path().getParent())).containsExactly("jdbc-driver.yaml");
    }
}
