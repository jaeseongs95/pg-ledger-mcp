package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.app.AdminBeansConfiguration;
import org.pgledger.admin.app.AdminMode;
import org.pgledger.admin.app.LedgerSourceProperties;
import org.pgledger.admin.app.Ui;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.database.driver.RegisteredDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * W7-EMB-02 and W7-BEAN-03: the console of the admin tests exists by the production path, not by a profile. The test registers
 * the PostgreSQL driver and leaves the product's DataSource configuration out of its scan, so {@code
 * DriverRegisteredCondition} (the one condition of the product) holds because the loader opens the registered jar, the mode is
 * {@code CONSOLE} because a {@code DataSource} exists, and the DataSource is the one of the test database. {@link
 * AdminMode} follows the {@code DataSource} and nothing else.
 */
@AdminTest
class ConsoleWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AdminMode mode;

    @Autowired
    private Ui ui;

    @Test
    @DisplayName("W7-EMB-02 a registered driver and the test's own DataSource: the mode is CONSOLE, the DataSource is the test one, and the DataSource configuration of the product is not in the context")
    void theConsoleExistsByTheProductionCondition() {
        assertThat(mode).isEqualTo(AdminMode.CONSOLE);
        assertThat(ui.console()).isTrue();
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(RegisteredDriver.class)).as("the driver of the product is not opened by the test").isEmpty();
        assertThat(ui.sourceUrl()).isEqualTo(LedgerSourceProperties.PUBLIC_REPOSITORY);
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(AdminBeansConfiguration.class);
    }

    @Test
    @DisplayName("W7-BEAN-03 AdminMode is CONSOLE with a DataSource in the context and SETUP without one")
    void theModeFollowsTheDataSource() {
        runner().withBean(DataSource.class, DriverManagerDataSource::new)
                .run(context -> assertThat(context.getBean(AdminMode.class)).isEqualTo(AdminMode.CONSOLE));
        runner().run(context -> assertThat(context.getBean(AdminMode.class)).isEqualTo(AdminMode.SETUP));
    }
}
