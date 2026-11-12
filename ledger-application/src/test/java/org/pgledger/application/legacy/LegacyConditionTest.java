package org.pgledger.application.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * W7-BEAN-01 (principle 7.2: the condition of a bean is tested with the runner): the legacy import is an operator command, so
 * its services exist only where {@code ledger.cli.enabled=true}. The command line tool sets it in its own settings; the MCP
 * server and the admin web never do, and so have neither bean.
 */
class LegacyConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LegacyRowMapper.class);

    @Test
    @DisplayName("W7-BEAN-01 the row mapper of the legacy import exists only for ledger.cli.enabled=true")
    void theMapperExistsOnlyForTheOperatorTool() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LegacyRowMapper.class));
        runner.withPropertyValues("ledger.cli.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LegacyRowMapper.class));
        runner.withPropertyValues("ledger.cli.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LegacyRowMapper.class));
    }

    /** Whether the class is registered with these settings: the condition is read when it is registered, its collaborators are not needed. */
    private static boolean registered(String... settings) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(settings).applyTo(context);
            context.register(LegacyImportService.class);
            return context.containsBeanDefinition("legacyImportService");
        }
    }

    @Test
    @DisplayName("W7-BEAN-01 the import service of the legacy ledger is registered only for ledger.cli.enabled=true")
    void theServiceExistsOnlyForTheOperatorTool() {
        assertThat(registered()).isFalse();
        assertThat(registered("ledger.cli.enabled=false")).isFalse();
        assertThat(registered("ledger.cli.enabled=true")).isTrue();
    }
}
