package org.pgledger.domain.model;

import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.ActorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.TextRule;

/**
 * One entry of an extensible code list. The key (group and code) never changes and entries are never deleted;
 * an entry is retired by setting its state to INACTIVE. English text falls back to the Korean text.
 */
public record CommonCode(CodeGroup group, CodeValue code, String nameKo, String nameEn, String descriptionKo,
        String descriptionEn, int sortOrder, CodeState state, ActorId createdBy) {

    public CommonCode {
        nameKo = TextRule.CODE_NAME.required(nameKo);
        nameEn = nameEn == null || nameEn.isBlank() ? nameKo : TextRule.CODE_NAME.required(nameEn);
        descriptionKo = TextRule.CODE_DESCRIPTION.optional(descriptionKo);
        descriptionEn = descriptionEn == null || descriptionEn.isBlank() ? descriptionKo
                : TextRule.CODE_DESCRIPTION.optional(descriptionEn);
    }

    public boolean active() {
        return state == CodeState.ACTIVE;
    }
}
