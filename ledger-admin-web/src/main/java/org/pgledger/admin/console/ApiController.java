package org.pgledger.admin.console;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only JSON of the admin: who is signed in and the overview. A client signs in with the admin token as
 * {@code Authorization: Bearer}; the browser pages use the session. There is no endpoint that changes the ledger.
 */
@RestController
public class ApiController {

    /** How the owner signed in. */
    public enum SignIn {
        TOKEN, OIDC
    }

    public record SessionView(boolean authenticated, String name, SignIn signIn) {
    }

    private final OverviewService overview;

    public ApiController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/api/session")
    SessionView session(Authentication authentication) {
        return new SessionView(authentication.isAuthenticated(), authentication.getName(),
                authentication instanceof OAuth2AuthenticationToken ? SignIn.OIDC : SignIn.TOKEN);
    }

    @GetMapping("/api/overview")
    OverviewService.Overview overview(@RequestParam(name = "project", required = false) String project) {
        return overview.overview(project);
    }
}
