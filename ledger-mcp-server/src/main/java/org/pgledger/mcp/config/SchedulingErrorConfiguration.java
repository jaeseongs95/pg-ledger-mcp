package org.pgledger.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one place where the failure of a scheduled task is logged (with its cause); the schedule goes on. */
@Configuration(proxyBeanMethods = false)
public class SchedulingErrorConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingErrorConfiguration.class);

    @Bean
    ThreadPoolTaskSchedulerCustomizer scheduledTaskErrorLogging() {
        return scheduler -> scheduler.setErrorHandler(failure -> LOG.error("a scheduled task failed", failure));
    }
}
