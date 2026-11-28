// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.veridger.admin.web.AdminHttpExceptionAdvice;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.admin.GrantService.SyncCommand;
import org.veridger.application.admin.GrantService.SyncResult;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The session-centric access control console ({@code /grants}): grants, per-project scope synchronization, and
 * revocations by session author ({@code <vendor>:<session_id>}).
 *
 * <p>Every state-changing endpoint requires an authenticated owner session (plus Spring Security CSRF protection) and
 * records the signed-in owner in {@code owner_audit}. No per-session public key or bearer token registration is
 * required before granting access.
 */
@Controller
public class GrantsController {

    /** Issues project grants using split host/sessionId UI fields or a combined {@code <host>:<session_id>} author. */
    public record AuthorGrantForm(String author, String host, String customHost, String sessionId, String project,
            List<String> scopes, String expiresAt, String note) {

        public String resolvedAuthor() {
            return SessionAuthorInput.resolve(host, customHost, sessionId, author);
        }

        public SessionAuthorInput.Split split(List<String> knownHosts) {
            if (sessionId != null && !sessionId.isBlank() && host != null && !host.isBlank()) {
                return new SessionAuthorInput.Split(host, customHost == null ? "" : customHost, sessionId);
            }
            if (author != null && !author.isBlank()) {
                return SessionAuthorInput.split(author, knownHosts);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                return new SessionAuthorInput.Split(SessionAuthorInput.defaultHost(knownHosts),
                        customHost == null ? "" : customHost, sessionId);
            }
            return SessionAuthorInput.split(author, knownHosts);
        }
    }

    /** Revokes project grants using split host/sessionId UI fields or a combined {@code <host>:<session_id>} author. */
    public record RevokeForm(String author, String host, String customHost, String sessionId, String project,
            String scope) {

        public String resolvedAuthor() {
            return SessionAuthorInput.resolve(host, customHost, sessionId, author);
        }

        public SessionAuthorInput.Split split(List<String> knownHosts) {
            if (sessionId != null && !sessionId.isBlank() && host != null && !host.isBlank()) {
                return new SessionAuthorInput.Split(host, customHost == null ? "" : customHost, sessionId);
            }
            if (author != null && !author.isBlank()) {
                return SessionAuthorInput.split(author, knownHosts);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                return new SessionAuthorInput.Split(SessionAuthorInput.defaultHost(knownHosts),
                        customHost == null ? "" : customHost, sessionId);
            }
            return SessionAuthorInput.split(author, knownHosts);
        }
    }

    /** Synchronizes the active scopes of one author on one project in a single transaction. */
    public record SyncForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    private final GrantService grants;
    private final Choices choices;
    private final GrantsUiProperties uiProperties;

    public GrantsController(GrantService grants, Choices choices, GrantsUiProperties uiProperties) {
        this.grants = grants;
        this.choices = choices;
        this.uiProperties = uiProperties;
    }

    @GetMapping("/grants")
    String page(Model model) {
        fill(model);
        return "grants";
    }

    @PostMapping("/grants/author")
    String grantAuthor(@ModelAttribute AuthorGrantForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel("authorGrantForm", form));
        grants.grant(new GrantCommand(form.resolvedAuthor(), form.project(), form.scopes(), form.expiresAt(), form.note()),
                actor);
        flash.addFlashAttribute("flashKey", "flash.grantIssued");
        return "redirect:/grants";
    }

    @PostMapping("/grants/sync")
    String sync(@ModelAttribute SyncForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel("syncForm", form));
        SyncResult result = grants.syncProjectScopes(
                new SyncCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()), actor);
        if (result.added() == 0 && result.revoked() == 0) {
            flash.addFlashAttribute("flashKey", "flash.grantUnchanged");
        } else {
            flash.addFlashAttribute("flashKey", "flash.grantSynced");
        }
        return "redirect:/grants";
    }

    @PostMapping("/grants/revoke")
    String revoke(@ModelAttribute RevokeForm form, OwnerIdentity actor, HttpServletRequest request,
            RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "grants", () -> recoveryModel("revokeGrantForm", form));
        grants.revoke(new RevokeCommand(form.resolvedAuthor(), form.project(), form.scope()), actor);
        flash.addFlashAttribute("flashKey", "flash.grantRevoked");
        return "redirect:/grants";
    }

    private void fill(Model model) {
        populateCommon(model.asMap());
    }

    private Map<String, Object> recoveryModel(String formKey, Object formValue) {
        Map<String, Object> model = new LinkedHashMap<>();
        populateCommon(model);
        model.put(formKey, formValue);
        return model;
    }

    private void populateCommon(Map<String, Object> target) {
        List<SessionGrant> liveGrants = grants.liveAll();
        Choices.ProjectChoice choice = choices.project(null);
        Map<String, String> projectNames = Choices.projectNames(choice.projects());
        List<SessionAccessView.SessionCard> sessions = SessionAccessView.build(liveGrants, List.copyOf(projectNames.keySet()));

        target.put("activeScopeCount", sessions.stream().mapToInt(SessionAccessView.SessionCard::totalGrantCount).sum());
        target.put("sessions", sessions);
        target.put("knownAuthors", SessionAccessView.knownAuthors(sessions));
        target.put("knownHosts", uiProperties.knownHosts());
        target.put("filterHosts", sessions.stream().map(SessionAccessView.SessionCard::host)
                .filter(host -> !host.isEmpty()).distinct().sorted().toList());
        target.put("projectNames", projectNames);
        target.put("choice", choice);
        target.put("scopes", Scope.assignable());
        target.put("scopeGroups", List.of("issue", "spec", "task", "criterion", "deliverable", "version"));
    }
}
