package org.pgledger.mcp.support;

import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.mcp.support.McpTestSchema.SchemaMigrated;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.pgledger.testing.TestOwners;

/**
 * What the MCP tests add to the server under test: the schema ({@link McpTestSchema}) and the defect categories of
 * an installation that has been set up (one active, one retired).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(McpTestSchema.class)
public class McpTestInfrastructure {

    /** Present once the categories are in the database. */
    public record CategoriesSeeded() {
    }

    /**
     * An HTTP route that fails the way a defect fails (MCC-PRN-01): behind the same security as {@code /mcp}, so the
     * request needs the bearer token. The text of its exception must never reach a client.
     */
    @RestController
    public static class FailingRoute {

        /** The path of the route. */
        public static final String PATH = "/probe/boom";

        @GetMapping(PATH)
        String boom() {
            throw new IllegalStateException(ProbeTools.SECRET_DETAIL);
        }
    }

    /**
     * Stops the scheduler of the server (the poll of the code revision) when the context starts to close, before the schema
     * of the context is dropped: the initializer of the test schema drops it on the same event, and a poll that runs between
     * the drop and the stop of the scheduler finds no table and logs an error that is no failure of the product (the log of
     * a failed poll is the behaviour that the product has, SchedulingErrorConfiguration). The listener has the highest
     * precedence, the initializer's is unordered, so this one runs first.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class SchedulerStoppedFirst implements ApplicationListener<ContextClosedEvent> {

        private final ThreadPoolTaskScheduler scheduler;

        SchedulerStoppedFirst(ThreadPoolTaskScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            scheduler.shutdown();
        }
    }

    @Bean
    SchedulerStoppedFirst schedulerStoppedFirst(ThreadPoolTaskScheduler scheduler) {
        return new SchedulerStoppedFirst(scheduler);
    }

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"), TestOwners.OWNER);
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"), TestOwners.OWNER);
        return new CategoriesSeeded();
    }
}
