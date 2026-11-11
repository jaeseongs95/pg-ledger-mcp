package org.pgledger.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.value.CodeValue;

/** The entries of one code list; decides whether a code may be written to a column. */
public record CodeCatalog(CodeGroup group, List<CommonCode> entries) {

    public CodeCatalog {
        entries = List.copyOf(entries);
    }

    /** The active codes, ordered by sort order then code. */
    public List<CodeValue> validCodes() {
        return entries.stream().filter(CommonCode::active)
                .sorted(Comparator.comparingInt(CommonCode::sortOrder).thenComparing(c -> c.code().value()))
                .map(CommonCode::code).toList();
    }

    /**
     * Checks the code of a required column about to be written.
     *
     * @param column    the column, for the message, for example {@code defect.severity}
     * @param candidate the code the write wants to store
     * @param current   the code the row holds now, or {@code null} for an insert
     * @throws ValidationException CODE_REQUIRED for none, CODE_INVALID for an unknown or retired code. A retired
     *         code is tolerated only when the row already holds it and the group is not always-active.
     */
    public void check(String column, CodeValue candidate, CodeValue current) {
        List<String> valid = validCodes().stream().map(CodeValue::value).toList();
        String validText = String.join(", ", valid);
        if (candidate == null) {
            throw new ValidationException(ValidationCode.CODE_REQUIRED, List.of(column, validText, group.name()),
                    List.of(new ValidationIssue(column, ValidationReason.REQUIRED, List.of(), valid)), Map.of("valid", valid));
        }
        CommonCode entry = entries.stream().filter(e -> e.code().equals(candidate)).findFirst().orElse(null);
        boolean known = entry != null;
        boolean retired = known && !entry.active();
        boolean unchanged = candidate.equals(current);
        if (!known || (retired && (group.alwaysActive() || current == null || !unchanged))) {
            boolean holdsLegacyValue = group.alwaysActive() && known;
            throw new ValidationException(ValidationCode.CODE_INVALID,
                    List.of(column, candidate.value(), group.name(), validText),
                    List.of(new ValidationIssue(column, ValidationReason.NOT_ACTIVE_CODE, List.of(), valid)),
                    Map.of("valid", valid, "holdsLegacyValue", List.of(Boolean.toString(holdsLegacyValue))));
        }
    }
}
