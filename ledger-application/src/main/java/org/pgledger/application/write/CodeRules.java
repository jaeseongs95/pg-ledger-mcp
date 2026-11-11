package org.pgledger.application.write;

import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.model.Defect;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.CodeValue;
import org.springframework.stereotype.Component;

/** The common-code rules of the defect columns, checked in Java against the codes read in the same transaction. */
@Component
public class CodeRules {

    private final CommonCodeRepository codes;

    public CodeRules(CommonCodeRepository codes) {
        this.codes = codes;
    }

    /**
     * Checks category and severity of a defect about to be written. Every write of the row passes through here:
     * severity is always-active, so a row that still holds a retired severity needs an active one in the same write.
     *
     * @param current the row as it is now, or {@code null} for an insert
     */
    public void checkDefect(Defect current, CodeValue category, CodeValue severity) {
        codes.catalog(CodeGroup.DEFECT_CATEGORY).check("defect.category", category,
                current == null ? null : current.category());
        codes.catalog(CodeGroup.DEFECT_SEVERITY).check("defect.severity", severity,
                current == null ? null : current.severity());
    }
}
