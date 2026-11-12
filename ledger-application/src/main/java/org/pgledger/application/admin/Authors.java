package org.pgledger.application.admin;

import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.AuthorId;

/** The author an owner operation names: the one reading of it, for the grants and for the session keys. */
final class Authors {

    private Authors() {
    }

    /**
     * The author of the text the owner gave: a missing text is a missing {@code author} (a parameter of the operation,
     * REQUIRED); the format is the domain's ({@link AuthorId}).
     *
     * @throws ValidationException REQUIRED when there is no text
     */
    static AuthorId of(String raw) {
        if (raw == null) {
            throw ValidationException.invalid("author", ValidationReason.REQUIRED);
        }
        return AuthorId.of(raw);
    }
}
