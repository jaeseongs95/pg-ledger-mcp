package org.pgledger.domain.error;

import java.util.Arrays;
import java.util.Objects;

/**
 * Why a value failed validation, as a closed set. A reason carries no sentence: the sentence is the
 * {@link java.text.MessageFormat} pattern under {@link #messageKey()} in the bundles {@code messages/ledger-reasons}
 * (English) and {@code messages/ledger-reasons_ko}; the data of the failure travels as the reason arguments of the
 * {@link ValidationIssue}, in the order of the placeholders {0}, {1} ... of the pattern.
 */
public enum ValidationReason {

    /** The value is missing or blank. */
    REQUIRED,
    /** {0} = the property that must be set as well. */
    REQUIRED_WHEN_SET,
    REQUIRED_WITH_SCOPE,
    REQUIRED_TO_EDIT,
    REQUIRED_FOR_CONDITION,
    REQUIRED_FOR_IMPORTED_OPERATIONS,
    /** {0} = the longest length in characters. */
    MUST_BE_AT_MOST_CHARS,
    /** {0} = the shortest, {1} = the longest length in characters. */
    MUST_BE_CHARS_RANGE,
    /** {0} = the shortest, {1} = the longest length in characters. */
    LEGACY_ACTOR_ID_LENGTH,
    /** {0} = the fewest, {1} = the most items. */
    MUST_HOLD_ITEMS,
    /** {0} = the value that was given. */
    UNKNOWN_VALUE,
    NOT_A_VALID_POSITION,
    CURSOR_DAMAGED,
    CURSOR_OTHER_LIST,
    CURSOR_OTHER_FILTERS,
    NOT_AN_INTEGER,
    /** {0} = the lowest, {1} = the highest value. */
    OUT_OF_RANGE,
    NOT_ACTIVE_CODE,
    /** {0} = the text of the JSON library about the damage. */
    INVALID_JSON,
    /** The name is not an argument of the tool. */
    UNKNOWN_ARGUMENT,
    /** {0} = the JSON type the argument must have. */
    WRONG_TYPE,
    /** {0}, {1} = the two things of which exactly one is wanted. */
    MUST_BE_EXACTLY_ONE_OF,
    /** {0} = the status in which the property is set. */
    SET_EXACTLY_WHEN_STATUS_IS,
    /** {0} = the operation for which the property is set. */
    SET_EXACTLY_FOR,
    MUST_NOT_BE_THE_DEFECT_ITSELF,
    /** {0} = the property it must differ from. */
    MUST_DIFFER_FROM,
    MUST_MENTION_A_FIELD,
    /** {0} = the operations the property exists for. */
    EXISTS_ONLY_FOR,
    NATIVE_ENTRY_NEEDS_DECLARED_AUTHOR,
    VERIFICATION_NEEDS_REVIEWER_CHANNEL,
    MUST_BE_AFTER_GRANT_TIME,
    /** {0} = the kind of entity. */
    HAS_NO_STATE,
    /** {0} = the format the text must match. */
    MUST_MATCH,
    /** {0} = the number of hex characters. */
    MUST_BE_LOWER_CASE_HEX,
    DEFECT_ID_FORMAT,
    MUST_BE_POSITIVE,
    /** {0} = the pattern of the id. */
    MUST_BE_UPPER_CASE_ID_MATCHING,
    MUST_NOT_BE_BLANK,
    MUST_BE_UUID,
    MUST_BE_ISO_8601_INSTANT,
    MUST_NAME_A_SCOPE,
    /** {0} = the kind of entity. */
    CANNOT_BE_SAVED,
    /** {0} = the kind of entity. */
    NOT_AN_EDITABLE_FIELD_OF,
    MUST_NOT_BE_NEGATIVE,
    /** {0} = the prefix the id must start with. */
    MUST_START_WITH;

    private static final String PREFIX = "reason.";

    /** The key of the pattern in the reason bundles. */
    public String messageKey() {
        return PREFIX + name();
    }

    /**
     * The reason of a Jakarta Validation message template: {@code {reason.REQUIRED}} is {@link #REQUIRED}.
     *
     * @throws IllegalArgumentException when the template is not the key of a reason in braces
     */
    public static ValidationReason ofTemplate(String jakartaMessageTemplate) {
        return Arrays.stream(values())
                .filter(reason -> Objects.equals("{" + reason.messageKey() + "}", jakartaMessageTemplate))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "not the template of a validation reason: " + jakartaMessageTemplate));
    }
}
