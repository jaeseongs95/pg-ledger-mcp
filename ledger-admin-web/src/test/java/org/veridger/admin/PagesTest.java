// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

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
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Seeds;
import org.veridger.admin.support.Seeds.Seeded;
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
    @DisplayName("REH3-ADM-01 a task whose id has the characters / # ? : and an id of 109 characters (free-text ids) are opened from the task list, and the history filter takes the long id")
    void reh3Adm01_freeTextIds() throws Exception {
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
            assertThat(link.group(1)).startsWith("/tasks/detail?id=");
            String detail = browser.perform(browser.get("/tasks/detail").param("id", id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(detail).contains(row[1]);
        }
        String history = page(browser, "/history?project=" + project.project());
        assertThat(history).containsPattern("name=\"entityId\"[^>]*maxlength=\"300\"");
        assertThat(page(browser, "/history?project=" + project.project() + "&entityId=" + longId)).contains(longId);
    }

    @Test
    @DisplayName("ADM-016: the overview, projects, issues, an issue, specs, a spec, tasks, criteria, history, grants and codes each show the seeded rows")
    void adm016_everyPageShowsItsRows() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "the first issue");
        String spec = seeds.spec(project, "the first spec");
        String task = seeds.task(project, "the first task");
        seeds.criterion(project, "R1", "2.0", "C1");
        seeds.dispute(issue, "https://evidence.example/1");
        Browser browser = Browser.signedIn(mvc);

        assertThat(page(browser, "/?project=" + project.project())).contains("mcp-status", "attention",
                "Work &amp; gate breakdown");
        assertThat(page(browser, "/projects")).contains(project.project(), project.code());
        assertThat(page(browser, "/issues?project=" + project.project())).contains(issue, "the first issue");
        assertThat(page(browser, "/issues/" + issue)).contains("symptom of the first issue",
                "cause of the first issue", "Recent changes");
        assertThat(page(browser, "/specs?project=" + project.project())).contains(spec, "the first spec");
        assertThat(page(browser, "/specs/detail?id=" + spec)).contains("summary of the first spec",
                "acceptance of the first spec");
        assertThat(page(browser, "/tasks?project=" + project.project())).contains(task, "the first task");
        assertThat(page(browser, "/tasks/detail?id=" + task)).contains("purpose of the first task");
        assertThat(page(browser, "/criteria?project=" + project.project())).contains("criterion C1", "2.0");
        assertThat(page(browser, "/history?project=" + project.project())).contains(issue, "CREATE");
        assertThat(page(browser, "/grants")).contains(Seeds.AUTHOR, project.project());
        assertThat(page(browser, "/codes")).contains("ISSUE_CATEGORY", "ARC", "RETIRED");
        browser.perform(browser.get("/api/overview?project=" + project.project()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stats.issues").value(1))
                .andExpect(jsonPath("$.stats.specs").value(1))
                .andExpect(jsonPath("$.mcp.state").value("NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("ADM-017: the history of a project that does not exist is an empty list with status 200")
    void adm017_unknownProjectHistory() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        assertThat(page(browser, "/history?project=nosuchproject")).contains("No rows.");
    }

    @Test
    @DisplayName("ADM-018: an issue that does not exist is 404 NOT_FOUND (a page for a browser, a problem for a client)")
    void adm018_unknownIssue() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.get("/issues/NOP-I-0001").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        String html = browser.perform(browser.get("/issues/NOP-I-0001")).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        assertThat(html).contains("NOT_FOUND").contains("class=\"skip-link\"");
    }

    @Test
    @DisplayName("ADM-003: skip link, page info, page-size select, previous and next (disabled on the first and the last page), :focus-visible in the CSS")
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
    @DisplayName("ADM-016/044: tasks with limit 1 show one row, the total and a next control; next and previous move by cursor; a row inserted meanwhile does not repeat or skip a row")
    void adm016_adm044_pagingByCursor() throws Exception {
        Seeded project = seeds.project();
        String oldest = seeds.task(project, "task oldest");
        seeds.tick();
        String middle = seeds.task(project, "task middle");
        seeds.tick();
        String newest = seeds.task(project, "task newest");
        Browser browser = Browser.signedIn(mvc);

        String first = page(browser, "/tasks?project=" + project.project() + "&limit=1");
        assertThat(first).contains(newest).doesNotContain(middle).contains("1 shown · 3 matching").contains("Page 1, 1 rows");
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
        seeds.dispute(seeds.issue(project, "i"), address);
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
        String issue = seeds.issue(project, "i");
        seeds.dispute(issue, address);
        seeds.observe(issue, "seen", address);
        Browser browser = Browser.signedIn(mvc);

        for (String html : List.of(page(browser, "/history?project=" + project.project()),
                page(browser, "/issues/" + issue))) {
            assertThat(html).contains(address).doesNotContain("href=\"" + address);
        }
    }

    @Test
    @DisplayName("ADM-006: markup in titles, observations and reasons is escaped on every page; no script is made")
    void adm006_escaping() throws Exception {
        Seeded project = seeds.project();
        String hostile = "<script>alert(\"x\")</script> & 'q'\nline";
        String issue = seeds.issue(project, hostile);
        seeds.observe(issue, hostile, "https://x.example/e");
        Browser browser = Browser.signedIn(mvc);

        for (String html : List.of(page(browser, "/issues?project=" + project.project()), page(browser, "/issues/" + issue),
                page(browser, "/history?project=" + project.project()))) {
            assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;alert");
        }
    }

    @Test
    @DisplayName("ADM-039: instants are <time datetime=...Z> in UTC; the page is the same under another JVM zone and names no zone")
    void adm039_utcInstants() throws Exception {
        Seeded project = seeds.project();
        seeds.issue(project, "utc");
        Browser browser = Browser.signedIn(mvc);

        String utc = page(browser, "/issues?project=" + project.project());
        TimeZone before = TimeZone.getDefault();
        String seoul;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            seoul = page(browser, "/issues?project=" + project.project());
        } finally {
            TimeZone.setDefault(before);
        }

        Matcher time = Pattern.compile("<time datetime=\"(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)\">(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)</time>")
                .matcher(utc);
        assertThat(time.find()).isTrue();
        assertThat(time.group(1)).isEqualTo(time.group(2));
        assertThat(withoutToken(seoul)).isEqualTo(withoutToken(utc)).doesNotContain("Asia/Seoul").doesNotContain("KST");
    }

    private static String withoutToken(String html) {
        return html.replaceAll("name=\"_csrf\" value=\"[^\"]+\"", "name=\"_csrf\"")
                .replaceAll("name=\"_csrf\" content=\"[^\"]+\"", "name=\"_csrf\"");
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
