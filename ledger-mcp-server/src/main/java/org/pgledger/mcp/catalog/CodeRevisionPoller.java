package org.pgledger.mcp.catalog;

import org.pgledger.mcp.config.LedgerMcpProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Reads the common-code revision at the configured interval so that a change made by another process (the admin
 * web, the command line) reaches the tools of the open sessions without a restart. A failure of one poll is logged
 * by the scheduler error handler and the next poll runs as usual.
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
