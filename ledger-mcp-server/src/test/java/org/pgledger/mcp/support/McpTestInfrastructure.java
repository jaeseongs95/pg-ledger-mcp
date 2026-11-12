package org.pgledger.mcp.support;

import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.mcp.support.McpTestSchema.SchemaMigrated;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"), TestOwners.OWNER);
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"), TestOwners.OWNER);
        return new CategoriesSeeded();
    }
}
