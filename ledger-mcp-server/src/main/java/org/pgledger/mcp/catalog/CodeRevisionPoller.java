package org.pgledger.mcp.catalog;

import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Reads the common-code revision at the configured interval so that a change made by another process (the admin
 * web, the command line) reaches the tools of the open sessions without a restart. A failure of one poll is logged
 * by the scheduler error handler and the next poll runs as usual.
 *
 * <p>A {@code @Configuration} class and not a component because {@link SchedulingConfigurer} is the extension point that
 * Spring's scheduling support calls on configuration classes (it is how a task is added to the registrar the framework
 * owns); a component that only has a {@code @Scheduled} method could not take the interval from a setting
 * ({@code ledger.mcp.code-poll-interval}) as a duration.
 */
@Configuration(proxyBeanMethods = false)
public class CodeRevisionPoller implements SchedulingConfigurer {

    private final ToolCatalog catalog;
    private final LedgerMcpProperties properties;

    public CodeRevisionPoller(ToolCatalog catalog, LedgerMcpProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(catalog::refreshIfChanged, properties.codePollInterval());
    }
}
