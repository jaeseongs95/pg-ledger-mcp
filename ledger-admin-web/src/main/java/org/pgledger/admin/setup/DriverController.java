package org.pgledger.admin.setup;

import org.pgledger.admin.security.OwnerActors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The driver page: what is registered, and the form that registers another (the owner only; also in setup mode). A
 * registration is recorded with the signed-in owner.
 */
@Controller
public class DriverController {

    /** The form: the jar of the driver and its {@code java.sql.Driver} class. */
    public record DriverForm(String jar, String className) {
    }

    private final DriverRegistrationService registrations;

    public DriverController(DriverRegistrationService registrations) {
        this.registrations = registrations;
    }

    @GetMapping("/driver")
    String page(@RequestParam(name = "registered", required = false) String registered, Model model) {
        DriverRegistrationService.State state = registrations.state();
        model.addAttribute("registered", state.registered().orElse(null));
        model.addAttribute("effective", state.effective());
        model.addAttribute("restartRequired", state.restartRequired());
        model.addAttribute("justRegistered", registered != null);
        return "driver";
    }

    @PostMapping("/driver")
    String register(@ModelAttribute DriverForm form, Authentication authentication) {
        registrations.register(form.jar(), form.className(), OwnerActors.of(authentication));
        return "redirect:/driver?registered";
    }
}
