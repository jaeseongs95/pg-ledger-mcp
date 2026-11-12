package org.pgledger.domain.model;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.Patch;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.domain.value.TextRule;

/**
 * Editable fields of a defect. The code columns category and severity are checked against the common codes
 * (CODE_REQUIRED, CODE_INVALID) by the code catalogue, not here.
 */
public record DefectFields(Patch<ProjectId> projectId, Patch<String> title, Patch<CodeValue> category,
        Patch<CodeValue> severity, Patch<String> behavior, Patch<String> rootCause, Patch<AuthorId> ownerActorId)
        implements EntityFields {

    /**
     * Normalises raw boundary values: required texts are validated, optional ones blank to null; behavior and root cause have
     * to be given and may be empty (REH3, the {@code save()} of 1.x).
     */
    public static DefectFields of(Patch<String> projectId, Patch<String> title, Patch<String> category,
            Patch<String> severity, Patch<String> behavior, Patch<String> rootCause, Patch<String> ownerActorId) {
        return new DefectFields(projectId.map(ProjectId::of), title.map(TextRule.TITLE::required),
                category.map(DefectFields::code), severity.map(DefectFields::code),
                behavior.map(TextRule.BEHAVIOR::givenOrEmpty), rootCause.map(TextRule.ROOT_CAUSE::givenOrEmpty),
                ownerActorId.map(DefectFields::author));
    }

    static CodeValue code(String raw) {
        return raw == null || raw.isBlank() ? null : CodeValue.of(raw);
    }

    static AuthorId author(String raw) {
        return raw == null || raw.isBlank() ? null : AuthorId.of(raw);
    }

    @Override
    public HistoryEntity entity() {
        return HistoryEntity.DEFECT;
    }

    @Override
    public DefectFields requireCreatable() {
        EntityFields.requireValue("project_id", projectId);
        EntityFields.requireValue("title", title);
        EntityFields.requireValue("behavior", behavior);
        EntityFields.requireValue("root_cause", rootCause);
        return this;
    }

    @Override
    public DefectFields requireEditable() {
        EntityFields.requireAnyMentioned(projectId, title, category, severity, behavior, rootCause, ownerActorId);
        return this;
    }
}
