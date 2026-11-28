// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Browser.Form;
import org.veridger.admin.support.Seeds;
import org.veridger.admin.support.Seeds.Seeded;
import org.veridger.application.admin.GrantService;
import org.veridger.domain.model.SessionGrant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/**
 * Verifies the v4.0.0 session-centric /grants access console: direct 1-step session grants without keys/tokens,
 * unified session cards in EN & KO, atomic single-transaction /grants/sync (add + remove + no-op + clear), CSRF
 * enforcement, and HTML 4xx recovery with preserved input.
 */
@AdminTest
class GrantsUxTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private GrantService grants;

    @Autowired
    private JdbcClient jdbc;

    private static String author(String prefix) {
        return "agent:" + prefix + "-" + SEQUENCE.incrementAndGet();
    }

    @Test
    @DisplayName("GUX-01: /grants groups granted sessions into session cards in EN and KO without key or token ceremony")
    void gux01_sessionCardsRenderInEnAndKo() throws Exception {
        Seeded project = seeds.project();
        String firstAuthor = author("first");
        String secondAuthor = author("second");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", Form.of("author", firstAuthor, "project",
                project.project(), "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/grants/author", Form.of("author", secondAuthor, "project",
                project.project(), "scopes", "spec"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        String enHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(enHtml).contains("data-session-card", "/grants/sync", "/grants/author")
                .contains(firstAuthor, secondAuthor)
                .contains("aria-label=\"Help for Verify\"", "completed specification, task, or criterion to Active")
                .doesNotContain("/grants/preview", "/grants/tokens", "/grants/keys/revoke");

        String koHtml = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(koHtml).contains("세션 권한 관리", "권한이 부여된 세션 목록", "변경 저장",
                "aria-label=\"검증 권한 도움말\"", "완료 보고된 명세·작업·출시 기준을 진행 중으로");
    }

    @Test
    @DisplayName("GUX-02: POST /grants/sync atomically adds new scopes, revokes removed scopes, keeps unchanged grant IDs, and records both audit entries")
    void gux02_syncAtomicallyAddsAndRevokesScopes() throws Exception {
        Seeded project = seeds.project();
        String session = author("sync");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "spec", "scopes", "task", "note", "initial"),
                browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());

        Long originalIssueId = grants.live(session).stream()
                .filter(g -> "issue".equals(g.scope().token())).findFirst().orElseThrow().id();
        Long originalTaskId = grants.live(session).stream()
                .filter(g -> "task".equals(g.scope().token())).findFirst().orElseThrow().id();

        MultiValueMap<String, String> syncForm = Form.of("author", session, "project", project.project(),
                "scopes", "issue", "scopes", "task", "scopes", "deliverable", "scopes", "verify",
                "note", "synced");
        browser.perform(browser.form("/grants/sync", syncForm, browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantSynced"));

        List<SessionGrant> after = grants.live(session);
        assertThat(after).extracting(g -> g.scope().token())
                .containsExactlyInAnyOrder("issue", "task", "deliverable", "verify");
        assertThat(after.stream().filter(g -> "issue".equals(g.scope().token())).findFirst().orElseThrow().id())
                .isEqualTo(originalIssueId);
        assertThat(after.stream().filter(g -> "task".equals(g.scope().token())).findFirst().orElseThrow().id())
                .isEqualTo(originalTaskId);

        List<String> ops = jdbc.sql("SELECT operation FROM owner_audit WHERE subject = :sub ORDER BY id")
                .param("sub", session + "|" + project.project()).query(String.class).list();
        assertThat(ops).containsExactly("GRANT_ISSUED", "GRANT_REVOKED", "GRANT_ISSUED");
    }

    @Test
    @DisplayName("GUX-03: POST /grants/sync handles no-op without audit churn and allows clearing all scopes")
    void gux03_syncNoOpAndClearAll() throws Exception {
        Seeded project = seeds.project();
        String session = author("sync-edge");
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/author", Form.of("author", session, "project",
                project.project(), "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());

        int auditBefore = jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :sub")
                .param("sub", session + "|" + project.project()).query(Integer.class).single();

        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project(),
                "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantUnchanged"));

        int auditAfterNoOp = jdbc.sql("SELECT COUNT(*) FROM owner_audit WHERE subject = :sub")
                .param("sub", session + "|" + project.project()).query(Integer.class).single();
        assertThat(auditAfterNoOp).isEqualTo(auditBefore);

        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project()),
                browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashKey", "flash.grantSynced"));
        assertThat(grants.live(session)).isEmpty();
    }

    @Test
    void selectingOnlyIssueCreationSplitsTheExistingGrantAndKeepsItsMetadata() throws Exception {
        Seeded project = seeds.project();
        String session = author("split");
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/grants");
        browser.perform(browser.form("/grants/author", Form.of("author", session, "project",
                project.project(), "scopes", "issue", "note", "original"), csrf))
                .andExpect(status().is3xxRedirection());
        var original = grants.live(session).getFirst();

        String html = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("이슈 생성", "이슈 수정", "명세 작성", "명세 수정")
                .contains("data-scope-chip=\"issue_create\"", "data-scope-chip=\"issue_update\"")
                .contains("이슈 전체", "명세 전체", "작업 전체", "출시 기준 전체", "산출물 전체", "버전 전체")
                .doesNotContain("data-scope-group=\"verify\"");

        browser.perform(browser.form("/grants/sync", Form.of("author", session, "project", project.project(),
                "scopes", "issue_create"), browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        assertThat(grants.live(session)).singleElement().satisfies(grant -> {
            assertThat(grant.scope().token()).isEqualTo("issue_create");
            assertThat(grant.note()).isEqualTo("original");
            assertThat(grant.grantedAt()).isEqualTo(original.grantedAt());
            assertThat(grant.expiresAt()).isEqualTo(original.expiresAt());
        });
    }

    @Test
    @DisplayName("GUX-04: POST /grants/sync enforces CSRF (403 without token) and owner authentication")
    void gux04_syncRequiresCsrfAndOwnerSession() throws Exception {
        Seeded project = seeds.project();
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants/sync",
                Form.of("author", author("no-csrf"), "project", project.project(), "scopes", "issue"), null)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        Browser anonymous = new Browser(mvc);
        anonymous.perform(anonymous.form("/grants/sync",
                Form.of("author", author("anon"), "project", project.project(), "scopes", "issue"),
                anonymous.csrfOf("/login")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GUX-05: POST /grants/author returns HTML 404 on unknown project with preserved author input and zero grants")
    void gux05_grantAuthorHtmlRecoveryOnUnknownProject() throws Exception {
        String badProjSession = author("badproj");
        Browser browser = Browser.signedIn(mvc);

        String recoveryHtml = browser.perform(browser.form("/grants/author",
                Form.of("author", badProjSession, "project", "no-such-project-gux", "scopes", "issue"),
                browser.csrfOf("/grants")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(recoveryHtml).contains("Could not save the form").contains("value=\"" + badProjSession + "\"");
        assertThat(grants.live(badProjSession)).isEmpty();
    }

    @Test
    @DisplayName("GUX-06: /grants renders split host and sessionId inputs, grants/revokes via split fields, strips duplicate host prefix, and preserves custom host on 4xx")
    void gux06_splitHostAndSessionIdInputs() throws Exception {
        Seeded project = seeds.project();
        String sid = "split-" + SEQUENCE.incrementAndGet();
        String customSid = "custom-" + SEQUENCE.incrementAndGet();
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"enrolled-host\"", "id=\"enrolled-session-id\"",
                "id=\"revoke-host\"", "id=\"revoke-session-id\"");

        // Grant using split host + sessionId (with redundant host prefix pasted in sessionId)
        browser.perform(browser.form("/grants/author", Form.of("host", "codex", "sessionId", "codex:" + sid,
                "project", project.project(), "scopes", "issue", "scopes", "task"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live("codex:" + sid)).extracting(g -> g.scope().token())
                .containsExactlyInAnyOrder("issue", "task");

        // Grant using custom host
        browser.perform(browser.form("/grants/author", Form.of("host", "custom", "customHost", "agent",
                "sessionId", customSid, "project", project.project(), "scopes", "spec"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live("agent:" + customSid)).extracting(g -> g.scope().token()).containsExactly("spec");

        // Session card renders host badge and session id cleanly
        String withCards = browser.perform(browser.get("/grants").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(withCards).contains("badge-host", "codex", sid, "data-author=\"codex:" + sid + "\"");

        // Revoke using split host + sessionId
        browser.perform(browser.form("/grants/revoke", Form.of("host", "codex", "sessionId", sid,
                "project", project.project()), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live("codex:" + sid)).isEmpty();

        // Locked session-card submission (host disabled/omitted, combined hidden author preserved) works for grant & revoke
        browser.perform(browser.form("/grants/author", Form.of("author", "codex:" + sid, "sessionId", sid,
                "project", project.project(), "scopes", "deliverable"), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live("codex:" + sid)).extracting(g -> g.scope().token()).containsExactly("deliverable");
        browser.perform(browser.form("/grants/revoke", Form.of("author", "codex:" + sid, "sessionId", sid,
                "project", project.project()), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live("codex:" + sid)).isEmpty();

        // HTML 4xx preserves custom host and sessionId in recovery modal
        String recovery = browser.perform(browser.form("/grants/author", Form.of("host", "custom",
                "customHost", "myhost", "sessionId", "rec-01", "project", "no-such-proj-split", "scopes", "issue"),
                browser.csrfOf("/grants")))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(recovery).contains("value=\"myhost\"", "value=\"rec-01\"", "value=\"myhost:rec-01\"");
    }
}
