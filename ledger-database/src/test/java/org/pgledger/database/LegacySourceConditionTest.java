package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.driver.LegacySourceConfiguration;
import org.pgledger.database.driver.LegacySourcePool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * W7-BEAN-02 (principle 7.2): the read side of the legacy import exists only when {@code ledger.import.source.url} is set, so
 * an application without the setting never opens a second connection. The runner has no registered driver, which the pool needs:
 * a context that wants the pool fails for that, and one that does not want it starts.
 */
class LegacySourceConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(LegacySourceConfiguration.class);

    @Test
    @DisplayName("W7-BEAN-02 without ledger.import.source.url the configuration of the legacy source is not in the context")
    void noUrlNoSource() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LegacySourceConfiguration.class)
                .doesNotHaveBean(LegacySourcePool.class));
    }

    @Test
    @DisplayName("W7-BEAN-02 with ledger.import.source.url the pool of the legacy source is wanted (here it fails for the driver that the runner does not have)")
    void aUrlWantsThePool() {
        runner.withPropertyValues("ledger.import.source.url=jdbc:postgresql://127.0.0.1:1/legacy").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("RegisteredDriver");
        });
    }
}
