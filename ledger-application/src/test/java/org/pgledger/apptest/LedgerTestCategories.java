package org.pgledger.apptest;

import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.pgledger.testing.TestOwners;

/**
 * The defect categories of an installation that has been set up: one active and one retired. A context without
 * this configuration is a fresh installation whose category list is still empty.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LedgerTestCategories {

    /** Present once the categories are in the database. */
    public record CategoriesSeeded() {
    }

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, LedgerTestInfrastructure.SchemaMigrated schema) {
        codes.set(new CodeCommand("DEFECT_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"), TestOwners.OWNER);
        codes.set(new CodeCommand("DEFECT_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"), TestOwners.OWNER);
        return new CategoriesSeeded();
    }
}
