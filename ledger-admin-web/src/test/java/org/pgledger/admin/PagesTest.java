package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Seeds;
import org.pgledger.admin.support.Seeds.Seeded;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-003, 004, 006, 016, 017, 018, 039, 044: what the pages show for a seeded project. The rows are made through the
 * real services; the pages are read through the real security chain.
 */
@AdminTest
class PagesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    private String page(Browser browser, String uri) throws Exception {
        return browser.perform(browser.get(uri)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    @DisplayName("REH3-ADM-01 a task whose id has the characters / # ? : and an id of 109 characters (ids of 1.x) are opened from the task list, and the history filter takes the long id")
    void reh3Adm01_idsOfOneX() throws Exception {
        Seeded project = seeds.project();
        String odd = "AGS#9/x.y:z_1?a=b";
        String longId = "ags:" + "x".repeat(105);
        seeds.task(project, odd, "odd id task");
        seeds.task(project, longId, "long id task");
        Browser browser = Browser.signedIn(mvc);

        String list = page(browser, "/tasks?project=" + project.project());

        for (String[] row : new String[][] {{odd, "odd id task"}, {longId, "long id task"}}) {
            Matcher link = Pattern.compile("<a href=\"([^\"]+)\"[^>]*>" + Pattern.quote(row[0]) + "</a>").matcher(list);
            assertThat(link.find()).as("the list links the task %s", row[0]).isTrue();
            String id = org.springframework.web.util.UriUtils.decode(org.springframework.web.util.UriComponentsBuilder
                    .fromUriString(link.group(1).replace("&amp;", "&")).build().getQueryParams().getFirst("id"),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(id).isEqualTo(row[0]);
            String detail = browser.perform(browser.get(link.group(1).replace("&amp;", "&"))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(detail).contains(row[1]);
        }
        String history = page(browser, "/history?project=" + project.project());
        assertThat(history).containsPattern("name=\"entityId\"[^>]*maxlength=\"300\"");
        assertThat(page(browser, "/history?project=" + project.project() + "&entityId=" + longId)).contains(longId);
    }

    @Test
    @DisplayName("ADM-016: the overview, projects, defects, a defect, tasks, conditions, history, grants and codes each show "
            + "the seeded rows")
    void adm016_everyPageShowsItsRows() throws Exception {
        Seeded project = seeds.project();
        String defect = seeds.defect(project, "the first defect");
        String task = seeds.task(project, "the first task");
        seeds.condition(project, "R1", "1.0", "C1");
        seeds.dispute(defect, "https://evidence.example/1");
        Browser browser = Browser.signedIn(mvc);

        assertThat(page(browser, "/?project=" + project.project())).contains("mcp-status", "attention", "Counts");
        assertThat(page(browser, "/projects")).contains(project.project(), project.code());
        assertThat(page(browser, "/defects?project=" + project.project())).contains(defect, "the first defect");
        assertThat(page(browser, "/defects/" + defect)).contains("behavior of the first defect",
                "cause of the first defect", "Recent changes");
        assertThat(page(browser, "/tasks?project=" + project.project())).contains(task, "the first task");
        assertThat(page(browser, "/tasks/detail?id=" + task)).contains("purpose of the first task");
        assertThat(page(browser, "/conditions?project=" + project.project())).contains("condition C1", "1.0");
        assertThat(page(browser, "/history?project=" + project.project())).contains(defect, "CREATE");
        assertThat(page(browser, "/grants")).contains(Seeds.AUTHOR, project.project());
        assertThat(page(browser, "/codes")).contains("DEFECT_CATEGORY", "ARC", "RETIRED");
        browser.perform(browser.get("/api/overview?project=" + project.project()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stats.defects").value(1))
                .andExpect(jsonPath("$.mcp.state").value("NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("ADM-017: the history of a project that does not exist is an empty list with status 200")
    void adm017_unknownProjectHistory() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        assertThat(page(browser, "/history?project=nosuchproject")).contains("No rows.");
    }

    @Test
    @DisplayName("ADM-018: a defect that does not exist is 404 NOT_FOUND (a page for a browser, a problem for a client)")
    void adm018_unknownDefect() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.get("/defects/NOP-D-0001").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        String html = browser.perform(browser.get("/defects/NOP-D-0001")).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        assertThat(html).contains("NOT_FOUND").contains("class=\"skip-link\"");
    }

    @Test
    @DisplayName("ADM-003: skip link, page info, page-size select, previous and next (disabled on the first and the last "
            + "page), :focus-visible in the CSS")
    void adm003_pagingControls() throws Exception {
        Seeded project = seeds.project();
        seeds.task(project, "task one");
        Browser browser = Browser.signedIn(mvc);

        String html = page(browser, "/tasks?project=" + project.project());

        assertThat(html).contains("class=\"skip-link\" href=\"#main\"").contains("class=\"page-info\"")
                .contains("<select id=\"limit\" name=\"limit\">");
        assertThat(count(html, "aria-disabled=\"true\"")).as("previous and next are disabled on a one-page list").isEqualTo(2);
        assertThat(css()).contains(":focus-visible");
    }

    @Test
    @DisplayName("ADM-016/044: tasks with limit 1 show one row, the total and a next control; next and previous move by cursor; "
            + "a row inserted meanwhile does not repeat or skip a row")
    void adm016_adm044_pagingByCursor() throws Exception {
        Seeded project = seeds.project();
        String oldest = seeds.task(project, "task oldest");
        seeds.tick();
        String middle = seeds.task(project, "task middle");
        seeds.tick();
        String newest = seeds.task(project, "task newest");
        Browser browser = Browser.signedIn(mvc);

        String first = page(browser, "/tasks?project=" + project.project() + "&limit=1");
        assertThat(first).contains(newest).doesNotContain(middle).contains("Rows in the project: 3").contains("Page 1, 1 rows");
        assertThat(count(first, "aria-disabled=\"true\"")).isEqualTo(1);
        String next = href(first, "rel=\"next\"");

        seeds.tick();
        String inserted = seeds.task(project, "task inserted");
        String second = page(browser, next);
        assertThat(second).contains(middle).doesNotContain(newest).doesNotContain(inserted).contains("Page 2, 1 rows");
        String previous = href(second, "rel=\"prev\"");
        assertThat(page(browser, previous)).contains(inserted).doesNotContain(middle);

        String third = page(browser, href(second, "rel=\"next\""));
        assertThat(third).contains(oldest).doesNotContain(middle).contains("Page 3, 1 rows");
        assertThat(count(third, "aria-disabled=\"true\"")).as("next is disabled on the last page").isEqualTo(1);
        assertThat(page(browser, href(third, "rel=\"prev\""))).contains(middle);
    }

    static Stream<String> evidenceAddresses() {
        return Stream.of("https://x.example/e", "http://x.example/e");
    }

    @ParameterizedTest
    @MethodSource("evidenceAddresses")
    @DisplayName("ADM-004: an http or https evidence address is a link")
    void adm004_httpAddressesAreLinks(String address) throws Exception {
        Seeded project = seeds.project();
        seeds.dispute(seeds.defect(project, "d"), address);
        Browser browser = Browser.signedIn(mvc);

        assertThat(page(browser, "/history?project=" + project.project())).contains("<a href=\"" + address + "\"");
    }

    static Stream<String> otherAddresses() {
        return Stream.of("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd");
    }

    @ParameterizedTest
    @MethodSource("otherAddresses")
    @DisplayName("ADM-004: any other scheme stays plain, escaped text and never becomes a link")
    void adm004_otherAddressesAreText(String address) throws Exception {
        Seeded project = seeds.project();
        String defect = seeds.defect(project, "d");
        seeds.dispute(defect, address);
        seeds.observe(defect, "seen", address);
        Browser browser = Browser.signedIn(mvc);

        for (String html : List.of(page(browser, "/history?project=" + project.project()),
                page(browser, "/defects/" + defect))) {
            assertThat(html).contains(address).doesNotContain("href=\"" + address);
        }
    }

    @Test
    @DisplayName("ADM-006: markup in titles, observations and reasons is escaped on every page; no script is made")
    void adm006_escaping() throws Exception {
        Seeded project = seeds.project();
        String hostile = "<script>alert(\"x\")</script> & 'q'\nline";
        String defect = seeds.defect(project, hostile);
        seeds.observe(defect, hostile, "https://x.example/e");
        Browser browser = Browser.signedIn(mvc);

        for (String html : List.of(page(browser, "/defects?project=" + project.project()), page(browser, "/defects/" + defect),
                page(browser, "/history?project=" + project.project()))) {
            assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert");
        }
    }

    @Test
    @DisplayName("ADM-039: instants are <time datetime=...Z> in UTC; the page is the same under another JVM zone and names no zone")
    void adm039_utcInstants() throws Exception {
        Seeded project = seeds.project();
        seeds.defect(project, "utc");
        Browser browser = Browser.signedIn(mvc);

        String utc = page(browser, "/defects?project=" + project.project());
        TimeZone before = TimeZone.getDefault();
        String seoul;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            seoul = page(browser, "/defects?project=" + project.project());
        } finally {
            TimeZone.setDefault(before);
        }

        Matcher time = Pattern.compile("<time datetime=\"(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)\">(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)</time>")
                .matcher(utc);
        assertThat(time.find()).isTrue();
        assertThat(time.group(1)).isEqualTo(time.group(2));
        assertThat(withoutToken(seoul)).isEqualTo(withoutToken(utc)).doesNotContain("Asia/Seoul").doesNotContain("KST");
    }

    /** The CSRF token is masked differently on every render; everything else must be equal. */
    private static String withoutToken(String html) {
        return html.replaceAll("name=\"_csrf\" value=\"[^\"]+\"", "name=\"_csrf\"");
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    private static String href(String html, String marker) {
        Matcher matcher = Pattern.compile("<a href=\"([^\"]+)\"[^>]*" + Pattern.quote(marker)).matcher(html);
        assertThat(matcher.find()).as("a link with " + marker).isTrue();
        return matcher.group(1).replace("&amp;", "&");
    }

    private static String css() throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "static", "css", "admin.css"));
    }
}
