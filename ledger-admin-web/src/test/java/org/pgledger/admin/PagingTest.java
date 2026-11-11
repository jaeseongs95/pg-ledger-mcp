package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.console.Paging;

/** ADM-003/044 at the smallest level: the links of a paged list walk forward and back along the cursors. */
class PagingTest {

    private static Map<String, String> filters() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("project", "p1");
        filters.put("owner", "");
        return filters;
    }

    @Test
    @DisplayName("ADM-003: the first page has no previous link; a next link exists only when the service has a next cursor")
    void firstAndLastPage() {
        Paging first = Paging.of("/tasks", filters(), 25, null, null, "C1");
        Paging only = Paging.of("/tasks", filters(), 25, null, null, null);

        assertThat(first.hasPrevious()).isFalse();
        assertThat(first.number()).isEqualTo(1);
        assertThat(first.nextHref()).isEqualTo("/tasks?project=p1&limit=25&cursor=C1&back=-");
        assertThat(only.hasPrevious()).isFalse();
        assertThat(only.hasNext()).isFalse();
    }

    @Test
    @DisplayName("ADM-044: previous goes back to the cursor that started the page before; the first page needs no cursor")
    void walkBack() {
        Paging second = Paging.of("/tasks", filters(), 25, "C1", "-", "C2");
        Paging third = Paging.of("/tasks", filters(), 25, "C2", "-,C1", null);

        assertThat(second.number()).isEqualTo(2);
        assertThat(second.previousHref()).isEqualTo("/tasks?project=p1&limit=25");
        assertThat(second.nextHref()).isEqualTo("/tasks?project=p1&limit=25&cursor=C2&back=-,C1");
        assertThat(third.number()).isEqualTo(3);
        assertThat(third.previousHref()).isEqualTo("/tasks?project=p1&limit=25&cursor=C1&back=-");
        assertThat(third.hasNext()).isFalse();
    }

    @Test
    @DisplayName("ADM-003: the page size is kept within 1 to 100 and defaults to 50")
    void limits() {
        assertThat(Paging.limitOf(null)).isEqualTo(50);
        assertThat(Paging.limitOf(0)).isEqualTo(1);
        assertThat(Paging.limitOf(1_000)).isEqualTo(100);
        assertThat(Paging.limitOf(25)).isEqualTo(25);
    }
}
