package org.pgledger.mcp.support;

import org.pgledger.application.admin.CommonCodeService.CodeCommand;

/** The common-code changes the catalog cases make, as the admin web or the command line would make them. */
public final class CodeChanges {

    public static final String CATEGORY = "DEFECT_CATEGORY";
    public static final String SEVERITY = "DEFECT_SEVERITY";
    public static final String RELATION = "RELATION_KIND";

    private CodeChanges() {
    }

    /** A code of a group with Korean and English texts derived from the code. */
    public static CodeCommand of(String group, String code, String state) {
        return new CodeCommand(group, code, "name " + code, "Name " + code, "description " + code,
                "Description " + code, 5, state);
    }

    public static CodeCommand active(String group, String code) {
        return of(group, code, "ACTIVE");
    }

    public static CodeCommand inactive(String group, String code) {
        return of(group, code, "INACTIVE");
    }

    /** The same code with texts of the given values (the sort order, the group and the state stay). */
    public static CodeCommand withTexts(String group, String code, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn) {
        return new CodeCommand(group, code, nameKo, nameEn, descriptionKo, descriptionEn, 5, "ACTIVE");
    }
}
