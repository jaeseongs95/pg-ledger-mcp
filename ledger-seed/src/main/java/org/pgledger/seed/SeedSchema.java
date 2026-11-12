package org.pgledger.seed;

import java.util.List;

/** The two tables of the bundle, shared by the writer ({@link SeedBuilder}) and the reader ({@link SeedBundle}). */
final class SeedSchema {

    /** The group of the codes of {@code defect-categories.tsv}: the code list of the defect categories of the ledger. */
    static final String CATEGORY_GROUP = "DEFECT_CATEGORY";
    static final String ACTIVE = "ACTIVE";

    static final String CODE_INSERT = "INSERT INTO seed_code (code_group, code, name_ko, name_en, description_ko, "
            + "description_en, sort_order, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    static final String CODE_SELECT = "SELECT code_group, code, name_ko, name_en, description_ko, description_en, "
            + "sort_order, state FROM seed_code ORDER BY sort_order, code_group, code";
    static final String DEFAULT_INSERT = "INSERT INTO install_default (default_name, default_value) VALUES (?, ?)";
    static final String DEFAULT_SELECT = "SELECT default_name, default_value FROM install_default ORDER BY default_name";

    static final List<String> DEFINITIONS = List.of(
            "CREATE TABLE seed_code (code_group VARCHAR(32) NOT NULL, code VARCHAR(32) NOT NULL, name_ko VARCHAR(200) NOT NULL, "
                    + "name_en VARCHAR(200) NOT NULL, description_ko VARCHAR(500) NOT NULL, description_en VARCHAR(500) NOT NULL, "
                    + "sort_order INTEGER NOT NULL, state VARCHAR(16) NOT NULL, PRIMARY KEY (code_group, code))",
            "CREATE TABLE install_default (default_name VARCHAR(64) NOT NULL, default_value VARCHAR(200) NOT NULL, "
                    + "PRIMARY KEY (default_name))");

    private SeedSchema() {
    }
}
