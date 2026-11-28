// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.veridger.application.admin.ProjectVersionService.ItemRef;
import org.veridger.application.gate.Target;
import org.veridger.domain.error.ValidationException;
import org.veridger.domain.error.ValidationReason;
import org.veridger.domain.rule.HistoryEntity;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.EntityId;
import org.veridger.domain.value.IssueId;
import org.veridger.domain.value.ProjectId;

record ProjectVersionLinks(Set<ItemRef> linked, Set<ItemRef> unlinked) {

    private static final int MAX_ITEMS = 200;

    static ProjectVersionLinks from(BatchOpInput.ProjectVersion input) {
        Set<ItemRef> linked = selection("link", input.linkIssueIds(), input.linkItems());
        Set<ItemRef> unlinked = selection("unlink", input.unlinkIssueIds(), input.unlinkItems());
        if (linked.stream().anyMatch(unlinked::contains)) {
            boolean issueFieldsOnly = input.linkItems() == null && input.unlinkItems() == null;
            throw ValidationException.invalid(issueFieldsOnly ? "unlink_issue_ids" : "unlink_items",
                    ValidationReason.MUST_DIFFER_FROM, issueFieldsOnly ? "link_issue_ids" : "link_items");
        }
        return new ProjectVersionLinks(linked, unlinked);
    }

    Stream<ItemRef> items() {
        return Stream.concat(linked.stream(), unlinked.stream());
    }

    List<Target> targets(ProjectId project, BatchOpInput.ProjectVersion input) {
        Set<Scope> scopes = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(input.create()) || input.originalName() != null || input.description() != null
                || (linked.isEmpty() && unlinked.isEmpty())) {
            scopes.add(Boolean.TRUE.equals(input.create()) ? Scope.VERSION_CREATE : Scope.VERSION_UPDATE);
        }
        items().map(item -> item.entity().updateScope()).forEach(scopes::add);
        return scopes.stream().map(scope -> new Target(project, scope)).toList();
    }

    private static Set<ItemRef> selection(String action, List<String> issueIds, BatchOpInput.VersionItems groups) {
        Set<ItemRef> items = new LinkedHashSet<>();
        int count = 0;
        if (issueIds != null) {
            count = issueIds.size();
            limit(action + "_issue_ids", count);
            append(items, HistoryEntity.ISSUE, issueIds);
        }
        if (groups != null) {
            String path = action + "_items";
            for (var group : groups.byEntity().entrySet()) {
                List<String> ids = group.getValue();
                count += ids.size();
                limit(path, count);
                append(items, group.getKey(), ids);
            }
        }
        return items;
    }

    private static void append(Set<ItemRef> items, HistoryEntity entity, List<String> ids) {
        for (String id : ids) {
            String validated = entity == HistoryEntity.ISSUE ? IssueId.of(id).value() : EntityId.of(id).value();
            items.add(new ItemRef(entity, validated));
        }
    }

    private static void limit(String path, int count) {
        if (count > MAX_ITEMS) {
            throw ValidationException.invalid(path, ValidationReason.MUST_HOLD_ITEMS, "0", Integer.toString(MAX_ITEMS));
        }
    }
}
