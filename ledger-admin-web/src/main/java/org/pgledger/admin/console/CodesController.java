package org.pgledger.admin.console;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.pgledger.admin.web.FormErrors;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.application.read.CatalogService;
import org.pgledger.application.read.CatalogService.CodesQuery;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.Token;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The common codes: the list (retired ones included), the form that adds or changes one, and the refusal to delete
 * one. A retired code is made active again only with the explicit confirmation of the form. The form is bound to the
 * closed sets ({@link CodeGroup}, {@link CodeState}): a value that is none of them fails the binding, which
 * {@link FormErrors} reports as the failure of invalid input with the allowed values.
 */
@Controller
public class CodesController {

    /**
     * The form; {@code confirmReactivation} is the checkbox that confirms making a retired code active again. A state that
     * is left empty is ACTIVE.
     */
    public record CodeForm(CodeGroup group, String code, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, Integer sortOrder, CodeState state, String confirmReactivation) {
    }

    private final CatalogService catalog;
    private final CommonCodeService codes;
    private final FormErrors forms;

    public CodesController(CatalogService catalog, CommonCodeService codes, FormErrors forms) {
        this.catalog = catalog;
        this.codes = codes;
        this.forms = forms;
    }

    @GetMapping("/codes")
    String page(Model model) {
        model.addAttribute("codes", catalog.codes(new CodesQuery(null, true, Choices.ALL, null)).items());
        model.addAttribute("groups", CodeGroup.values());
        model.addAttribute("states", CodeState.values());
        return "codes";
    }

    @PostMapping("/codes")
    String set(@Valid @ModelAttribute CodeForm form, BindingResult errors, OwnerIdentity actor) {
        forms.raiseIfAny(errors);
        requireConfirmationToReactivate(form);
        codes.set(new CodeCommand(token(form.group()), form.code(), form.nameKo(), form.nameEn(),
                form.descriptionKo(), form.descriptionEn(), form.sortOrder(), token(form.state())), actor);
        return "redirect:/codes";
    }

    /** Common codes are never deleted: a retired code stays and is set to INACTIVE instead. */
    @DeleteMapping("/codes")
    String deleteByMethod() {
        throw new AdminException(AdminCode.COMMON_CODE_DELETE_FORBIDDEN, List.of());
    }

    @PostMapping("/codes/delete")
    String deleteByForm() {
        throw new AdminException(AdminCode.COMMON_CODE_DELETE_FORBIDDEN, List.of());
    }

    /** The service takes the texts of the wire; a value that was left empty stays empty (the service defaults it). */
    private static String token(Token value) {
        return value == null ? null : value.token();
    }

    private void requireConfirmationToReactivate(CodeForm form) {
        boolean makesActive = form.state() == null || form.state() == CodeState.ACTIVE;
        if (!makesActive || form.confirmReactivation() != null || form.group() == null || form.code() == null) {
            return;
        }
        // the list shows the state of a code as the text of its wire token
        boolean retired = catalog.codes(new CodesQuery(form.group().token(), true, Choices.ALL, null)).items().stream()
                .anyMatch(existing -> Objects.equals(existing.code(), form.code())
                        && Objects.equals(CodeState.INACTIVE.token(), existing.state()));
        if (retired) {
            throw ValidationException.invalid("confirm_reactivation", ValidationReason.REQUIRED);
        }
    }
}
