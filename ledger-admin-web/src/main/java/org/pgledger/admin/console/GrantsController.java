package org.pgledger.admin.console;

import java.util.List;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.domain.rule.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** The live session grants: the list, the form that issues grants (one per scope) and the form that revokes them. */
@Controller
public class GrantsController {

    /** The issue form: the scopes are the checked boxes. */
    public record GrantForm(String author, String project, List<String> scopes, String expiresAt, String note) {
    }

    private final GrantService grants;
    private final Choices choices;

    public GrantsController(GrantService grants, Choices choices) {
        this.grants = grants;
        this.choices = choices;
    }

    @GetMapping("/grants")
    String page(Model model) {
        model.addAttribute("grants", grants.liveAll());
        model.addAttribute("choice", choices.project(null));
        model.addAttribute("scopes", Scope.values());
        return "grants";
    }

    @PostMapping("/grants")
    String grant(@ModelAttribute GrantForm form) {
        grants.grant(new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()));
        return "redirect:/grants";
    }

    @PostMapping("/grants/revoke")
    String revoke(@ModelAttribute RevokeCommand command) {
        grants.revoke(command);
        return "redirect:/grants";
    }
}
