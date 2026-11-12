package org.pgledger.admin.console;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.admin.web.FormErrors;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.PresentedKey;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
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
 *
 * <p>The checks of the forms that need no knowledge of the ledger are the constraints of the form records ({@link FormErrors}
 * turns a violation into the one failure of invalid input). What needs a computed value stays here: whether the fingerprint
 * the owner repeated is the fingerprint of the pasted key.
 */
@Controller
public class GrantsController {

    /** The issue form with the pasted public key (the key is required: the grant screen registers it). */
    public record GrantForm(String author, String project, List<String> scopes, String expiresAt, String note,
            @NotNull(message = FormErrors.REQUIRED) String publicKey) {
    }

    /**
     * What the confirmation adds: the fingerprint that the owner saw and compared (and the box that says so), and for a
     * replacement the fingerprint of the key that is replaced.
     */
    @ComparedFingerprint
    public record Confirmation(String fingerprint, String confirmFingerprint, String replace,
            String replaceFingerprint) {
    }

    /**
     * What the owner compares before confirming: the fingerprint of the pasted key, whether the author has that key
     * already. The key the author has now, when it is another one, is the model attribute {@code currentKey}.
     */
    public record Preview(GrantForm form, String fingerprint, String server, boolean alreadyRegistered) {
    }

    /** The form that closes a key: the author and the fingerprint of its live key, typed by the owner. */
    public record KeyRevokeForm(String author, String fingerprint) {
    }

    private final GrantService grants;
    private final SessionKeyService keys;
    private final Choices choices;
    private final FormErrors forms;

    public GrantsController(GrantService grants, SessionKeyService keys, Choices choices, FormErrors forms) {
        this.grants = grants;
        this.keys = keys;
        this.choices = choices;
        this.forms = forms;
    }

    @GetMapping("/grants")
    String page(Model model) {
        fill(model);
        return "grants";
    }

    /**
     * Shows the fingerprint of the pasted key (and the key the author has now) before anything is stored. The owner may
     * paste the public key alone or the block that the hook prints; for a block the server it was made for is shown next
     * to the fingerprint, and the block must be for the author of the form.
     */
    @PostMapping("/grants/preview")
    String preview(@Valid @ModelAttribute GrantForm form, BindingResult formErrors, Model model) {
        forms.raiseIfAny(formErrors);
        PresentedKey presented = keys.present(form.author(), form.publicKey());
        Optional<SessionPublicKey> current = keys.live(form.author());
        boolean alreadyRegistered = current.map(live -> Objects.equals(live.fingerprint(), presented.fingerprint()))
                .orElse(false);
        fill(model);
        current.filter(live -> !Objects.equals(live.fingerprint(), presented.fingerprint()))
                .ifPresent(other -> model.addAttribute("currentKey", other));
        // the confirmation carries the bare key, whichever of the two forms was pasted
        GrantForm shown = new GrantForm(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note(),
                presented.publicKey());
        model.addAttribute("preview", new Preview(shown, presented.fingerprint(), presented.server(),
                alreadyRegistered));
        return "grants";
    }

    @PostMapping("/grants")
    String grant(@Valid @ModelAttribute GrantForm form, BindingResult formErrors,
            @Valid @ModelAttribute Confirmation confirmation, BindingResult confirmationErrors, OwnerIdentity actor) {
        forms.raiseIfAny(formErrors);
        PresentedKey presented = keys.present(form.author(), form.publicKey());
        forms.raiseIfAny(confirmationErrors);
        requireTheKeysFingerprint(confirmation, presented.fingerprint());
        RegisterCommand key = new RegisterCommand(form.author(), presented.publicKey(), null);
        if (confirmation.replace() == null) {
            keys.register(key, actor);
        } else {
            keys.registerReplacing(key, actor, confirmation.replaceFingerprint());
        }
        grants.grant(new GrantCommand(form.author(), form.project(), form.scopes(), form.expiresAt(), form.note()),
                actor);
        return "redirect:/grants";
    }

    @PostMapping("/grants/revoke")
    String revoke(@ModelAttribute RevokeCommand command, OwnerIdentity actor) {
        grants.revoke(command, actor);
        return "redirect:/grants";
    }

    /**
     * Closes the live key of an author: the owner names the author and types the fingerprint of the key (shown in the
     * table of live keys) to confirm that exactly this key is meant. The author keeps its grants but cannot sign until
     * another key is registered. Recorded with the signed-in owner.
     */
    @PostMapping("/grants/keys/revoke")
    String revokeKey(@ModelAttribute KeyRevokeForm form, OwnerIdentity actor) {
        keys.revokeConfirmed(form.author(), form.fingerprint(), actor);
        return "redirect:/grants";
    }

    private void fill(Model model) {
        model.addAttribute("grants", grants.liveAll());
        model.addAttribute("keys", keys.liveKeys());
        model.addAttribute("choice", choices.project(null));
        model.addAttribute("scopes", Scope.values());
    }

    /** The owner repeated the fingerprint of the pasted key (letters of either case): compared with the computed one. */
    private static void requireTheKeysFingerprint(Confirmation confirmation, String fingerprint) {
        if (!Objects.equals(fingerprint, confirmation.fingerprint().toLowerCase(Locale.ROOT))) {
            throw ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, fingerprint);
        }
    }
}
