package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** ADM-013, ADM-015: the CSRF token of Spring Security decides; the ledger workflow has no endpoint here. */
@AdminTest
class CsrfTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("ADM-013: a post without the token, with a wrong token, or with only X-Requested-With is 403 CSRF_REJECTED; "
            + "with the token it succeeds")
    void adm013_csrfToken() throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/projects");

        browser.perform(browser.form("/projects", Form.of("id", "csrfa", "code", "CSRFA", "title", "t"), null)
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        browser.perform(browser.form("/projects", Form.of("id", "csrfa", "code", "CSRFA", "title", "t"),
                "not-the-token").accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        browser.perform(browser.form("/projects", Form.of("id", "csrfa", "code", "CSRFA", "title", "t"), null)
                .header("X-Requested-With", "XMLHttpRequest").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        browser.perform(browser.form("/projects", Form.of("id", "csrfa", "code", "CSRFA", "title", "t"), csrf))
                .andExpect(status().is3xxRedirection());
    }

    static Stream<String> workflowPaths() {
        return Stream.of("/api/save", "/api/transition", "/api/correct", "/api/record_fix", "/api/review");
    }

    @ParameterizedTest
    @MethodSource("workflowPaths")
    @DisplayName("ADM-015: there is no ledger-workflow endpoint (404 or 405), also for a signed-in owner with a token")
    void adm015_noWorkflowEndpoint(String path) throws Exception {
        Browser browser = Browser.signedIn(mvc);
        String csrf = browser.csrfOf("/projects");

        int get = browser.perform(browser.get(path).accept(MediaType.APPLICATION_JSON)).andReturn().getResponse()
                .getStatus();
        int post = browser.perform(browser.form(path, Form.of("x", "y"), csrf).accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();

        assertThat(List.of(get, post)).allMatch(code -> code == 404 || code == 405);
    }
}
