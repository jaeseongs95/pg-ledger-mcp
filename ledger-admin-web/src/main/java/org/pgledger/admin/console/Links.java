package org.pgledger.admin.console;

import org.pgledger.domain.rule.HistoryEntity;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Component;

/**
 * What the pages may turn into a link: evidence addresses are shown as links only when they are http or https, and the
 * item of the attention list goes to the page of its kind.
 */
@Component("links")
public class Links {

    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";

    /** True for an address that starts with {@code http://} or {@code https://} (any case). */
    public boolean isHttp(String address) {
        return address != null && (address.regionMatches(true, 0, HTTP, 0, HTTP.length())
                || address.regionMatches(true, 0, HTTPS, 0, HTTPS.length()));
    }

    /**
     * The page of an item of the attention list, as the link base the page gives the {@code id} to: the defect page
     * ({@code /defects/{id}}, the id is a path segment) for a defect and the task page ({@code /tasks/detail}, the id is a query
     * parameter: the ids of 1.x may contain a slash) for a task. The kind of the item is decided as the {@link HistoryEntity} that its text
     * names, not by comparing the text in the page.
     *
     * @throws org.pgledger.domain.error.ValidationException when the text names no kind of row
     * @throws IllegalArgumentException for a kind of row that has no page of its own (the attention list holds only
     *         defects and tasks)
     */
    public String itemPage(String entity) {
        return switch (Tokens.parse("entity", entity, HistoryEntity.values())) {
            case DEFECT -> "/defects/{id}";
            case TASK -> "/tasks/detail";
            case CONDITION, DEFECT_FIX, DEFECT_TASK, DEFECT_RELATION, CONDITION_LINK, DEFECT_CASE ->
                throw new IllegalArgumentException("the attention list has no item of the kind " + entity);
        };
    }
}
