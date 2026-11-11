package org.pgledger.admin.console;

import java.util.List;
import java.util.Objects;
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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The common codes: the list (retired ones included), the form that adds or changes one, and the refusal to delete
 * one. A retired code is made active again only with the explicit confirmation of the form.
 */
@Controller
public class CodesController {

    private static final int ALL = 1000;

    /** The form; {@code confirmReactivation} is the checkbox that confirms making a retired code active again. */
    public record CodeForm(String group, String code, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, Integer sortOrder, String state, String confirmReactivation) {
    }

    private final CatalogService catalog;
    private final CommonCodeService codes;

    public CodesController(CatalogService catalog, CommonCodeService codes) {
        this.catalog = catalog;
        this.codes = codes;
    }

    @GetMapping("/codes")
    String page(Model model) {
        model.addAttribute("codes", catalog.codes(new CodesQuery(null, true, ALL, null)).items());
        model.addAttribute("groups", CodeGroup.values());
        model.addAttribute("states", CodeState.values());
        return "codes";
    }

    @PostMapping("/codes")
    String set(@ModelAttribute CodeForm form) {
        requireConfirmationToReactivate(form);
        codes.set(new CodeCommand(form.group(), form.code(), form.nameKo(), form.nameEn(), form.descriptionKo(),
                form.descriptionEn(), form.sortOrder(), form.state()));
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

    private void requireConfirmationToReactivate(CodeForm form) {
        boolean makesActive = form.state() == null || form.state().isBlank()
                || Objects.equals(CodeState.ACTIVE.name(), form.state());
        if (!makesActive || form.confirmReactivation() != null || form.group() == null || form.code() == null) {
            return;
        }
        boolean retired = catalog.codes(new CodesQuery(form.group(), true, ALL, null)).items().stream()
                .anyMatch(existing -> Objects.equals(existing.code(), form.code())
                        && Objects.equals(CodeState.INACTIVE.name(), existing.state()));
        if (retired) {
            throw ValidationException.invalid("confirm_reactivation", ValidationReason.REQUIRED);
        }
    }
}
