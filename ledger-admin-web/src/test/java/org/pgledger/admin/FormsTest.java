package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.pgledger.testing.TestOwners;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AuthorKeys;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.Seeds;
import org.pgledger.admin.support.Seeds.Seeded;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.GrantService;
import org.pgledger.database.repository.ProjectRepository;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.ProjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/** ADM-005, 019 .. 024, 034, 043: the forms of the owner (projects, grants, common codes) and what they refuse. */
@AdminTest
class FormsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private GrantService grants;

    @Autowired
    private CommonCodeService codes;

    /** A form post of a signed-in owner with the token of the page that has the form, asking for a problem detail. */
    private org.springframework.test.web.servlet.ResultActions post(Browser browser, String page, String uri,
            MultiValueMap<String, String> fields) throws Exception {
        return browser.perform(browser.form(uri, fields, browser.csrfOf(page)).accept(MediaType.APPLICATION_JSON));
    }

    private record Invalid(String name, MultiValueMap<String, String> fields) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Invalid> invalidProjects() {
        return Stream.of(
                new Invalid("id with a blank", Form.of("id", "bad id", "code", "ABC", "title", "t")),
                new Invalid("code in lower case", Form.of("id", "okid", "code", "abc", "title", "t")),
                new Invalid("empty title", Form.of("id", "okid", "code", "ABC", "title", "")),
                new Invalid("title of 201 characters", Form.of("id", "okid", "code", "ABC", "title", "x".repeat(201))));
    }

    @ParameterizedTest
    @MethodSource("invalidProjects")
    @DisplayName("ADM-019: an invalid project form is 400 VALIDATION_FAILED")
    void adm019_invalidProject(Invalid input) throws Exception {
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/projects", "/projects", input.fields()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].path").exists());
    }

    @Test
    @DisplayName("ADM-019/043: a valid project is created (redirect) and stamped user:owner; a duplicate is 409 DUPLICATE_KEY")
    void adm019_validAndDuplicateProject() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        MultiValueMap<String, String> fields = Form.of("id", "formsp1", "code", "FORMSP1", "title", "A project");

        browser.perform(browser.form("/projects", fields, browser.csrfOf("/projects"))).andExpect(status().is3xxRedirection());

        assertThat(projects.findById(ProjectId.of("formsp1")).orElseThrow().createdBy().value()).isEqualTo("user:owner");
        assertThat(browser.perform(browser.get("/projects")).andReturn().getResponse().getContentAsString())
                .contains("formsp1", "FORMSP1");
        post(browser, "/projects", "/projects", fields).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_KEY"));
    }

    @Test
    @DisplayName("ADM-020/021/043: grants are issued per scope, listed, revoked by scope and then completely")
    void adm020_adm021_grantsLifecycle() throws Exception {
        Seeded project = seeds.project();
        String author = "gemini:admin-ui-test";
        Browser browser = Browser.signedIn(mvc);

        browser.perform(browser.form("/grants", AuthorKeys.newPair().confirmed(Form.of("author", author, "project", project.project(), "scopes", "defect",
                "scopes", "task", "expiresAt", "2099-12-31T00:00:00Z", "note", "a note")), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactlyInAnyOrder("defect", "task");
        assertThat(grants.live(author)).allMatch(g -> "user:owner".equals(g.grantedBy().value()));
        assertThat(browser.perform(browser.get("/grants")).andReturn().getResponse().getContentAsString())
                .contains(author, "a note");

        browser.perform(browser.form("/grants/revoke", Form.of("author", author, "project", project.project(), "scope",
                "task"), browser.csrfOf("/grants"))).andExpect(status().is3xxRedirection());
        assertThat(grants.live(author)).extracting(g -> g.scope().token()).containsExactly("defect");

        browser.perform(browser.form("/grants/revoke", Form.of("author", author), browser.csrfOf("/grants")))
                .andExpect(status().is3xxRedirection());
        assertThat(grants.live(author)).isEmpty();
    }

    static Stream<Invalid> invalidGrants() {
        return Stream.of(
                new Invalid("no author", Form.of("author", "", "project", "p", "scopes", "defect")),
                new Invalid("bad author", Form.of("author", "no-colon", "project", "p", "scopes", "defect")),
                new Invalid("no scope", Form.of("author", "claude:a", "project", "p")),
                new Invalid("unknown scope", Form.of("author", "claude:a", "project", "p", "scopes", "root")),
                new Invalid("bad expiry", Form.of("author", "claude:a", "project", "p", "scopes", "defect", "expiresAt",
                        "tomorrow")));
    }

    @ParameterizedTest
    @MethodSource("invalidGrants")
    @DisplayName("ADM-020: an invalid grant form is refused with a coded 4xx and issues nothing")
    void adm020_invalidGrant(Invalid input) throws Exception {
        Seeded project = seeds.project();
        MultiValueMap<String, String> fields = Form.of();
        input.fields().forEach((name, values) -> values.forEach(value -> fields.add(name,
                "p".equals(value) ? project.project() : value)));
        AuthorKeys.newPair().confirmed(fields);
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants", "/grants", fields).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").exists());

        assertThat(grants.liveAll()).noneMatch(g -> "claude:a".equals(g.authorId().value()));
    }

    @Test
    @DisplayName("ADM-021: revoking a scope without a project is 400 VALIDATION_FAILED; revoking without an author is too")
    void adm021_revokeNeedsWhatItNeeds() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/grants", "/grants/revoke", Form.of("author", "claude:admin-ui-test", "scope", "defect"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].path").value("project"));
        post(browser, "/grants", "/grants/revoke", Form.of("author", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("ADM-022: a common code is never deleted: DELETE and the delete form answer 405 COMMON_CODE_DELETE_FORBIDDEN")
    void adm022_noDelete() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/codes");

        browser.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/codes")
                .session(browser.session()).param("_csrf", csrf).accept(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(browser.address());
                    return r;
                })).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON_CODE_DELETE_FORBIDDEN"));
        post(browser, "/codes", "/codes/delete", Form.of("group", "DEFECT_CATEGORY", "code", "ARC"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("COMMON_CODE_DELETE_FORBIDDEN"));
    }

    static Stream<Invalid> invalidCodes() {
        return Stream.of(
                new Invalid("unknown group", Form.of("group", "X", "code", "ABC", "nameKo", "n")),
                new Invalid("bad code", Form.of("group", "DEFECT_CATEGORY", "code", "bad code", "nameKo", "n")),
                new Invalid("empty names", Form.of("group", "DEFECT_CATEGORY", "code", "ABC", "nameKo", "")),
                new Invalid("description of 501 characters", Form.of("group", "DEFECT_CATEGORY", "code", "ABC", "nameKo",
                        "n", "descriptionKo", "x".repeat(501))),
                new Invalid("english description of 501 characters", Form.of("group", "DEFECT_CATEGORY", "code", "ABC",
                        "nameKo", "n", "descriptionEn", "x".repeat(501))));
    }

    @ParameterizedTest
    @MethodSource("invalidCodes")
    @DisplayName("ADM-023: an invalid code form is 400 VALIDATION_FAILED")
    void adm023_invalidCode(Invalid input) throws Exception {
        Browser browser = Browser.signedIn(mvc);

        post(browser, "/codes", "/codes", input.fields()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("ADM-024: a code is created, then set INACTIVE; the row stays listed as inactive; the revision moves")
    void adm024_createAndRetireCode() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        long before = codes.set(new CommonCodeService.CodeCommand("DEFECT_CATEGORY", "TMPCAT", "임시", "Temp", null, null,
                null, "ACTIVE"), TestOwners.OWNER).revision();

        browser.perform(browser.form("/codes", Form.of("group", "DEFECT_CATEGORY", "code", "UI_UX", "nameKo", "화면",
                "nameEn", "UI", "state", "ACTIVE"), browser.csrfOf("/codes"))).andExpect(status().is3xxRedirection());
        browser.perform(browser.form("/codes", Form.of("group", "DEFECT_CATEGORY", "code", "UI_UX", "nameKo", "화면",
                "nameEn", "UI", "state", "INACTIVE"), browser.csrfOf("/codes"))).andExpect(status().is3xxRedirection());

        List<CommonCode> listed = codes.list(CodeGroup.DEFECT_CATEGORY);
        assertThat(listed).anyMatch(code -> "UI_UX".equals(code.code().value()) && code.state() == CodeState.INACTIVE);
        String html = browser.perform(browser.get("/codes")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("UI_UX").contains("badge-INACTIVE");
        assertThat(codes.set(new CommonCodeService.CodeCommand("DEFECT_CATEGORY", "TMPCAT", "임시", "Temp", null, null,
                null, "ACTIVE"), TestOwners.OWNER).revision()).as("the revision is bumped by the two changes").isGreaterThan(before);
    }

    @Test
    @DisplayName("ADM-005: a retired code becomes active again only with the explicit confirmation")
    void adm005_reactivationNeedsConfirmation() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        MultiValueMap<String, String> retire = Form.of("group", "DEFECT_CATEGORY", "code", "REACT", "nameKo", "r", "state",
                "INACTIVE");
        browser.perform(browser.form("/codes", retire, browser.csrfOf("/codes"))).andExpect(status().is3xxRedirection());

        post(browser, "/codes", "/codes", Form.of("group", "DEFECT_CATEGORY", "code", "REACT", "nameKo", "r", "state",
                "ACTIVE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("confirm_reactivation"));
        assertThat(codes.list(CodeGroup.DEFECT_CATEGORY)).anyMatch(code -> "REACT".equals(code.code().value())
                && code.state() == CodeState.INACTIVE);

        browser.perform(browser.form("/codes", Form.of("group", "DEFECT_CATEGORY", "code", "REACT", "nameKo", "r", "state",
                "ACTIVE", "confirmReactivation", "yes"), browser.csrfOf("/codes"))).andExpect(status().is3xxRedirection());
        assertThat(codes.list(CodeGroup.DEFECT_CATEGORY)).anyMatch(code -> "REACT".equals(code.code().value())
                && code.state() == CodeState.ACTIVE);
    }

    @Test
    @DisplayName("ADM-034: a body above the limit is 413 PAYLOAD_TOO_LARGE")
    void adm034_oversizeBody() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/projects");

        browser.perform(browser.form("/projects", Form.of(), csrf).content("id=big&code=BIG&title=" + "x".repeat(100_000))
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
}
