package org.pgledger.admin.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Paging of a list that the services page by cursor. The page keeps the cursors of the pages before it in the
 * {@code back} parameter (a marker for the first page), so "previous" needs no offset and no total: the link goes back
 * to the cursor that started the previous page. The page that follows is the page of the next cursor of the
 * service, so paging stays stable when rows are inserted (the same rule as the keyset cursors of the tools).
 *
 * @param limit        rows per page
 * @param number       the number of this page, counted from 1
 * @param previousHref the link to the previous page, absent on the first page
 * @param nextHref     the link to the next page, absent on the last page
 */
public record Paging(int limit, int number, String previousHref, String nextHref) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    private static final String FIRST_PAGE = "-";

    /** The page size choices of the page-size select. */
    public static final List<Integer> SIZES = List.of(25, 50, 100);

    public boolean hasPrevious() {
        return previousHref != null;
    }

    public boolean hasNext() {
        return nextHref != null;
    }

    /** The rows per page asked for, within 1 to {@value #MAX_LIMIT}; 50 when absent. */
    public static int limitOf(Integer requested) {
        return requested == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    /**
     * @param path       the path of the list page
     * @param filters    the filters of the list, by parameter name; blank ones are left out of the links
     * @param limit      rows per page as asked for
     * @param cursor     the cursor that started this page, absent on the first page
     * @param back       the cursors of the pages before this one
     * @param nextCursor the cursor of the next page, absent on the last page
     */
    public static Paging of(String path, Map<String, String> filters, Integer limit, String cursor, String back,
            String nextCursor) {
        int size = limitOf(limit);
        List<String> trail = StringUtils.hasText(back)
                ? List.of(StringUtils.commaDelimitedListToStringArray(back)) : List.of();
        boolean first = !StringUtils.hasText(cursor);
        String previousHref = null;
        if (!first) {
            String last = trail.isEmpty() ? FIRST_PAGE : trail.getLast();
            List<String> before = trail.isEmpty() ? List.of() : trail.subList(0, trail.size() - 1);
            previousHref = href(path, filters, size, FIRST_PAGE.equals(last) ? null : last, before);
        }
        String nextHref = null;
        if (nextCursor != null) {
            List<String> after = new ArrayList<>(trail);
            after.add(first ? FIRST_PAGE : cursor);
            nextHref = href(path, filters, size, nextCursor, after);
        }
        return new Paging(size, first ? 1 : trail.size() + 1, previousHref, nextHref);
    }

    private static String href(String path, Map<String, String> filters, int limit, String cursor, List<String> back) {
        Map<String, String> parameters = new LinkedHashMap<>(filters);
        parameters.put("limit", Integer.toString(limit));
        parameters.put("cursor", cursor);
        parameters.put("back", back.isEmpty() ? null : String.join(",", back));
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        parameters.forEach((name, value) -> {
            if (StringUtils.hasText(value)) {
                uri.queryParam(name, value);
            }
        });
        return uri.build().encode().toUriString();
    }
}
