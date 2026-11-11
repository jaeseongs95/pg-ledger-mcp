package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.admin.support.AuthorKeys;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Browser.Form;
import org.pgledger.admin.support.Seeds;
import org.pgledger.application.admin.GrantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADM-057 (security audit U3): a {@code DPoP} header, or the {@code DPoP} authorization scheme, never turns the token of
 * an MCP agent into the owner. The admin knows the bearer scheme only; a DPoP proof here is text without a meaning.
 */
@AdminTest
class DpopTest {

    private static final String PROOF = "eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2In0.eyJqdGkiOiJ4In0.c2ln";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private GrantService grants;

    @Autowired
    private Seeds seeds;

    private ResultActions get(Browser browser, String authorization) throws Exception {
        return browser.perform(browser.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, authorization).header("DPoP", PROOF));
    }

    @Test
    @DisplayName("ADM-057: the agent token with a DPoP header is still AGENT_TOKEN_FORBIDDEN")
    void adm057_agentBearerWithAProof() throws Exception {
        get(new Browser(mvc), "Bearer " + AdminTestSettings.AGENT_TOKEN).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_TOKEN_FORBIDDEN"));
    }

    @Test
    @DisplayName("ADM-057: the DPoP authorization scheme is not accepted at all, for the agent token and for the admin token")
    void adm057_dpopSchemeIsNotAccepted() throws Exception {
        get(new Browser(mvc), "DPoP " + AdminTestSettings.AGENT_TOKEN).andExpect(status().isUnauthorized());
        get(new Browser(mvc), "DPoP " + AdminTestSettings.TOKEN).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("ADM-057: an agent cannot issue a grant by bearer, with or without a DPoP header, in either scheme")
    void adm057_noGrantByAnAgentToken() throws Exception {
        Seeds.Seeded project = seeds.project();
        int before = grants.liveAll().size();
        AuthorKeys.Pair key = AuthorKeys.newPair();

        for (String authorization : new String[] {"Bearer " + AdminTestSettings.AGENT_TOKEN,
            "DPoP " + AdminTestSettings.AGENT_TOKEN}) {
            Browser agent = new Browser(mvc);
            agent.perform(agent.form("/grants", key.confirmed(Form.of("author", "claude:dpop-agent", "project",
                    project.project(), "scopes", "defect")), null).accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, authorization).header("DPoP", PROOF))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(grants.liveAll()).hasSize(before);
    }
}
