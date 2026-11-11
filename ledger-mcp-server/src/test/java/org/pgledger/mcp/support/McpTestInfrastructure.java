package org.pgledger.mcp.support;

import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.mcp.support.McpTestSchema.SchemaMigrated;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

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

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"));
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"));
        return new CategoriesSeeded();
    }
}
