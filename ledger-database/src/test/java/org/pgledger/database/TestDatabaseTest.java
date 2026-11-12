package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.database.support.Databases;
import org.pgledger.database.support.LedgerDatabaseExtension;
import org.pgledger.testing.database.TestDatabase;
import org.pgledger.testing.database.TestDatabaseSettings;
import org.pgledger.testing.database.TestSchema;

/**
 * TDB-001 .. TDB-004 (TEST_CASES.md 1.4a): the tests really run on the PostgreSQL of the test PC, the schema of a test
 * exists while it runs and is gone after, the credentials come from a file by its path, and the application account is
 * never replaced by the owner account.
 */
@ExtendWith(LedgerDatabaseExtension.class)
class TestDatabaseTest {

    /** A made-up value that stands for a password in the tests of the file reading (it opens nothing). */
    private static final String FILE_VALUE = "value-of-the-file-1";

    @TempDir
    Path temp;

    private static TestDatabaseSettings settings(Map<String, String> properties) {
        return TestDatabaseSettings.from(properties::get);
    }

    private static Map<String, String> base() {
        Map<String, String> properties = new HashMap<>();
        properties.put(TestDatabaseSettings.URL_PROPERTY, "jdbc:postgresql://127.0.0.1:1/none");
        properties.put(TestDatabaseSettings.USER_PROPERTY, "owner");
        properties.put(TestDatabaseSettings.APP_USER_PROPERTY, "app");
        return properties;
    }

    @Test
    @DisplayName("TDB-001 the tests run on PostgreSQL 18 or later, in the database of the configured URL")
    void theTestDatabaseIsPostgreSql() throws SQLException {
        TestDatabase database = TestDatabase.shared();
        try (Connection connection = database.pool().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            assertThat(meta.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(meta.getDatabaseMajorVersion()).isGreaterThanOrEqualTo(18);
            String url = database.settings().url();
            String name = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
            assertThat(connection.getCatalog()).isEqualTo(name);
        }
    }

    @Test
    @DisplayName("TDB-002 a schema of the test support exists while its test runs and is gone after the drop")
    void aSchemaLivesOnlyAsLongAsItsTest(Databases databases) {
        TestDatabase database = databases.database();
        TestSchema schema = databases.newSchema();
        assertThat(database.exists(schema.name())).isTrue();
        assertThat(database.testSchemas()).contains(schema.name());

        schema.close();

        assertThat(database.exists(schema.name())).as("dropped by the end of its test").isFalse();
        assertThat(database.testSchemas()).doesNotContain(schema.name());
    }

    @Test
    @DisplayName("TDB-002 the sweep drops a schema of the name pattern older than one hour and keeps a younger one")
    void theSweepDropsOnlyTheOldLeftovers(Databases databases) {
        TestDatabase database = databases.database();
        TestSchema old = database.newSchema(Instant.now().minus(Duration.ofHours(2)));
        TestSchema young = databases.newSchema();

        List<String> dropped = database.sweep(Duration.ofHours(1));

        assertThat(dropped).contains(old.name()).doesNotContain(young.name());
        assertThat(database.exists(old.name())).isFalse();
        assertThat(database.exists(young.name())).isTrue();
    }

    @Test
    @DisplayName("TDB-003 a password file set by its path gives the pool its first line (without the line break); no path sets no password")
    void thePasswordComesFromTheFileOrFromNowhere() throws IOException {
        Path owner = temp.resolve("owner.pw");
        Files.writeString(owner, FILE_VALUE + "\r\n", StandardCharsets.UTF_8);
        Path app = temp.resolve("app.pw");
        Files.writeString(app, "other-" + FILE_VALUE, StandardCharsets.UTF_8);
        Map<String, String> properties = base();
        properties.put(TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY, owner.toString());
        properties.put(TestDatabaseSettings.APP_PASSWORD_FILE_PROPERTY, app.toString());
        TestDatabaseSettings withFiles = settings(properties);

        assertThat(withFiles.ownerPassword()).contains(FILE_VALUE);
        assertThat(withFiles.appPassword()).contains("other-" + FILE_VALUE);
        HikariConfig config = TestDatabase.config(withFiles, withFiles.user(), withFiles.ownerPassword().orElse(null), 1,
                "UTC");
        assertThat(config.getPassword()).isEqualTo(FILE_VALUE);

        TestDatabaseSettings without = settings(base());
        assertThat(without.ownerPassword()).isEmpty();
        assertThat(without.appPassword()).isEmpty();
        HikariConfig none = TestDatabase.config(without, without.user(), without.ownerPassword().orElse(null), 1, "UTC");
        assertThat(none.getPassword()).as("no password: the driver falls back to the pass file of the user").isNull();
        assertThat(none.getUsername()).isEqualTo("owner");
        // the settings print no path content and no password
        assertThat(withFiles.toString()).doesNotContain(FILE_VALUE);
    }

    @Test
    @DisplayName("TDB-003 a missing, an unreadable or an empty password file fails with a message that names the property and the path and never the content")
    void aBadPasswordFileNamesThePropertyAndThePath() throws IOException {
        Path missing = temp.resolve("missing.pw");
        Map<String, String> properties = base();
        properties.put(TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY, missing.toString());
        assertThatThrownBy(() -> settings(properties).ownerPassword()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY)
                .hasMessageContaining(missing.toString());

        Path empty = Files.writeString(temp.resolve("empty.pw"), "\n", StandardCharsets.UTF_8);
        properties.put(TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY, empty.toString());
        assertThatThrownBy(() -> settings(properties).ownerPassword()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY)
                .hasMessageContaining(empty.toString());

        // a directory cannot be read as a file: the message names the property and the path, with the cause attached
        properties.put(TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY, temp.toString());
        assertThatThrownBy(() -> settings(properties).ownerPassword()).hasMessageContaining(
                TestDatabaseSettings.OWNER_PASSWORD_FILE_PROPERTY).hasMessageContaining(temp.toString()).hasCauseInstanceOf(
                IOException.class);
    }

    @Test
    @DisplayName("TDB-004 an application account that cannot log in fails the test with a message that names the property; the owner account is not used instead")
    void anApplicationAccountThatCannotLogInFailsClearly() {
        TestDatabaseSettings real = TestDatabaseSettings.fromSystemProperties();
        TestDatabaseSettings broken = new TestDatabaseSettings(real.url(), real.user(), "ledger_no_such_account",
                real.ownerPasswordFile(), real.appPasswordFile());
        TestDatabase database = new TestDatabase(broken);

        assertThatThrownBy(database::appPool).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestDatabaseSettings.APP_USER_PROPERTY)
                .hasMessageContaining("ledger_no_such_account");
    }
}
