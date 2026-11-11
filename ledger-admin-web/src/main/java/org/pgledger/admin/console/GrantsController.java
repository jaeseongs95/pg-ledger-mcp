package org.pgledger.admin.console;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.admin.security.OwnerActors;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The live session grants: the list, the form that issues grants (one per scope) and the form that revokes them.
 *
 * <p>A grant is issued together with the public key of the session it is for, because the server accepts a write only
 * when it is signed with the key registered for its author. The owner pastes the key the session printed (hook command
 * {@code session-key}), the preview shows the key's fingerprint so that the owner can compare it with the one the session
 * printed, and only the confirmation that repeats this fingerprint registers the key and issues the grants. The key is
 * registered first and the grants are issued second, each as the service defines its transaction (the services retry and
 * commit by themselves); a refused grant after a registered key leaves the key registered, which grants nothing. An author
 * that already has another live key is not changed silently: the owner has to confirm the replacement of that exact key,
 * which the preview shows with its registration time. Every operation is recorded with the signed-in owner.
 */
@Controller
public class GrantsController {

    /**
     * The forms of the screen: the issue form with the pasted public key; the confirmation adds the fingerprint that the
     * owner saw and compared, and for a replacement the fingerprint of the key that is replaced.
     */
    public record GrantForm(String author, String project, List<String> scopes, String expiresAt, String note,
            String publicKey, String fingerprint, String confirmFingerprint, String replace, String replaceFingerprint) {
    }

    /**
     * What the owner compares before confirming: the fingerprint of the pasted key, and the key the author has now (if it
     * is another one) with its registration time.
     */
    public record Preview(GrantForm form, String fingerprint, boolean alreadyRegistered, String currentFingerprint,
            Instant currentRegisteredAt) {
    }

    private final GrantService grants;
    private final SessionKeyService keys;
    private final Choices choices;

    public GrantsController(GrantService grants, SessionKeyService keys, Choices choices) {
        this.grants = grants;
        this.keys = keys;
        this.choices = choices;
    }

    @GetMapping("/grants")
    String page(Model model) {
        fill(model);
        return "grants";
    }

    /** Shows the fingerprint of the pasted key (and the key the author has now) before anything is stored. */
    @PostMapping("/grants/preview")
    String preview(@ModelAttribute GrantForm form, Model model) {
        String fingerprint = keys.fingerprintOf(requireKey(form));
        Optional<SessionPublicKey> current = keys.live(form.author());
        boolean alreadyRegistered = current.filter(live -> Objects.equals(live.fingerprint(), fingerprint)).isPresent();
        Optional<SessionPublicKey> other = current.filter(live -> !Objects.equals(live.fingerprint(), fingerprint));
        fill(model);
        model.addAttribute("preview", new Preview(form, fingerprint, alreadyRegistered,
                other.map(SessionPublicKey::fingerprint).orElse(null),
                other.map(SessionPublicKey::registeredAt).orElse(null)));
        return "grants";
    }

    @PostMapping("/grants")
    String grant(@ModelAttribute GrantForm form, Authentication authentication) {
        OwnerIdentity actor = OwnerActors.of(authentication);
        String fingerprint = keys.fingerprintOf(requireKey(form));
        requireComparison(form, fingerprint);
        RegisterCommand key = new RegisterCommand(form.author(), form.publicKey(), null);
        if (form.replace() == null) {
            keys.register(key, actor);
        } else {
            keys.registerReplacing(key, actor, form.replaceFingerprint());
        }
        grants.grant(new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()),
                actor);
        return "redirect:/grants";
    }

    @PostMapping("/grants/revoke")
    String revoke(@ModelAttribute RevokeCommand command, Authentication authentication) {
        grants.revoke(command, OwnerActors.of(authentication));
        return "redirect:/grants";
    }

    private void fill(Model model) {
        model.addAttribute("grants", grants.liveAll());
        model.addAttribute("choice", choices.project(null));
        model.addAttribute("scopes", Scope.values());
    }

    private static String requireKey(GrantForm form) {
        if (form.publicKey() == null) {
            throw ValidationException.invalid("public_key", ValidationReason.REQUIRED);
        }
        return form.publicKey();
    }

    /** The owner compared the fingerprint: the form repeats the one of the key and the box is checked. */
    private static void requireComparison(GrantForm form, String fingerprint) {
        if (form.fingerprint() == null || form.confirmFingerprint() == null) {
            throw ValidationException.invalid("fingerprint", ValidationReason.REQUIRED);
        }
        if (!Objects.equals(fingerprint, form.fingerprint().toLowerCase(Locale.ROOT))) {
            throw ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, fingerprint);
        }
    }
}
