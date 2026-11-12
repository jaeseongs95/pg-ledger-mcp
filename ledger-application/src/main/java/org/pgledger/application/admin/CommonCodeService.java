package org.pgledger.application.admin;

import java.util.List;
import java.util.Optional;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.OwnerIdentity;
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
    private final OwnerAuditWriter audit;

    public CommonCodeService(CommonCodeRepository codes, CodeRevisionRepository revision, OwnerAuditWriter audit) {
        this.codes = codes;
        this.revision = revision;
        this.audit = audit;
    }

    /** Sets the code and, when that changes anything, records who did it in the owner's audit trail. */
    public CodeResult set(CodeCommand command, OwnerIdentity actor) {
        CodeGroup group = Tokens.parse("group", command.group(), CodeGroup.values());
        CodeValue code = CodeValue.of(command.code());
        CodeState state = command.state() == null || command.state().isBlank() ? CodeState.ACTIVE
                : Tokens.parse("state", command.state(), CodeState.values());
        Optional<CommonCode> existing = codes.find(group, code);
        int sort = command.sortOrder() != null ? command.sortOrder()
                : existing.map(CommonCode::sortOrder).orElse(DEFAULT_SORT_ORDER);
        CommonCode wanted = new CommonCode(group, code, command.nameKo(), command.nameEn(),
                command.descriptionKo(), command.descriptionEn(), sort, state,
                existing.map(CommonCode::createdBy).orElse(AuthorId.OWNER));
        if (existing.filter(wanted::equals).isPresent()) {
            // the code is what the owner asked for already: nothing to write, no revision, no audit row
            return new CodeResult(wanted, revision.current());
        }
        if (existing.isPresent()) {
            codes.update(wanted);
        } else {
            codes.insert(wanted);
        }
        revision.bump();
        audit.record(actor, OwnerAction.CODE_SET, group.name() + "/" + code.value(),
                (existing.isEmpty() ? "added" : "changed") + " state=" + state.name());
        return new CodeResult(wanted, revision.current());
    }

    @Transactional(readOnly = true)
    public List<CommonCode> list(CodeGroup group) {
        return codes.catalog(group).entries();
    }

    /** The code revision: it moves whenever a code is changed, so a reader of the codes can tell when to read them again. */
    @Transactional(readOnly = true)
    public long revision() {
        return revision.current();
    }
}
