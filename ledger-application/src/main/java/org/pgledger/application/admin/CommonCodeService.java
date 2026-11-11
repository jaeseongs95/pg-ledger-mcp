package org.pgledger.application.admin;

import java.util.List;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The extensible code lists. A code is added or updated (names, descriptions, order, state); it is never deleted
 * or renamed, because rows keep pointing at it, and there is no method that could. A change bumps the code
 * revision in the same transaction so other processes notice; setting a code to what it already is changes
 * nothing and does not bump.
 */
@Service
@Transactional
public class CommonCodeService {

    /** The default position of a code that has none. */
    public static final int DEFAULT_SORT_ORDER = 100;

    /** A code to set; the English texts fall back to the Korean ones, the state to ACTIVE. */
    public record CodeCommand(String group, String code, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, Integer sortOrder, String state) {
    }

    /** The code as stored and the revision after the call. */
    public record CodeResult(CommonCode code, long revision) {
    }

    private final CommonCodeRepository codes;
    private final CodeRevisionRepository revision;

    public CommonCodeService(CommonCodeRepository codes, CodeRevisionRepository revision) {
        this.codes = codes;
        this.revision = revision;
    }

    public CodeResult set(CodeCommand command) {
        CodeGroup group = Tokens.parse("group", command.group(), CodeGroup.values());
        CodeValue code = CodeValue.of(command.code());
        CodeState state = command.state() == null || command.state().isBlank() ? CodeState.ACTIVE
                : Tokens.parse("state", command.state(), CodeState.values());
        CommonCode existing = codes.find(group, code).orElse(null);
        int sort = command.sortOrder() != null ? command.sortOrder()
                : existing != null ? existing.sortOrder() : DEFAULT_SORT_ORDER;
        CommonCode wanted = new CommonCode(group, code, command.nameKo(), command.nameEn(),
                command.descriptionKo(), command.descriptionEn(), sort, state,
                existing != null ? existing.createdBy() : AuthorId.OWNER);
        if (existing == null) {
            codes.insert(wanted);
        } else if (existing.equals(wanted)) {
            return new CodeResult(existing, revision.current());
        } else {
            codes.update(wanted);
        }
        revision.bump();
        return new CodeResult(wanted, revision.current());
    }

    @Transactional(readOnly = true)
    public List<CommonCode> list(CodeGroup group) {
        return codes.catalog(group).entries();
    }
}
