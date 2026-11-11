package org.pgledger.admin.setup;

import java.util.ArrayList;
import java.util.List;
import org.pgledger.domain.error.AdminCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The login page: the token form and, when OpenID Connect is configured, one link per registered provider. */
@Controller
public class LoginController {

    /** A configured OpenID Connect provider: the registration id (the path of its sign-in) and its display name. */
    public record Provider(String id, String name) {
    }

    private final List<Provider> providers = new ArrayList<>();

    public LoginController(ObjectProvider<ClientRegistrationRepository> clients) {
        if (clients.getIfAvailable() instanceof Iterable<?> registrations) {
            for (Object registration : registrations) {
                if (registration instanceof ClientRegistration client) {
                    providers.add(new Provider(client.getRegistrationId(), client.getClientName()));
                }
            }
        }
    }

    @GetMapping("/login")
    String page(@RequestParam(name = "error", required = false) AdminCode error,
            @RequestParam(name = "logout", required = false) String logout, Model model) {
        model.addAttribute("providers", providers);
        model.addAttribute("errorKey", error == null ? null : error.messageKey());
        model.addAttribute("loggedOut", logout != null);
        return "login";
    }
}
