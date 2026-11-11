package org.pgledger.domain.model;

import java.util.HashSet;
import java.util.Set;
import org.pgledger.domain.value.Token;

/**
 * The six operations a batch can hold (review is not one of them). The key sets are the external contract that
 * the JSON boundary checks: required keys must be present, unknown keys are rejected.
 */
public enum BatchOpKind implements Token {
    SAVE("save", Set.of("entity", "fields", "reason"), Set.of("id", "expected_version")),
    TRANSITION("transition", Set.of("entity", "id", "expected_version", "to", "reason"),
            Set.of("evidence_uri", "evidence_sha256", "merged_into")),
    RECORD_FIX("record_fix", Set.of("defect_id", "repo", "commit_sha", "reason"),
            Set.of("branch", "ci_ref", "ci_conclusion", "ci_completed_at", "mark_source_fixed")),
    RECORD_CASE("record_case", Set.of("defect_id", "observation", "reason"),
            Set.of("evidence", "user_quote", "observed_at")),
    LINK("link", Set.of("kind", "a", "b", "reason"), Set.of("remove")),
    CORRECT("correct", Set.of("history_id", "reason"), Set.of());

    private final String token;
    private final Set<String> required;
    private final Set<String> optional;

    BatchOpKind(String token, Set<String> required, Set<String> optional) {
        this.token = token;
        this.required = required;
        this.optional = optional;
    }

    @Override
    public String token() {
        return token;
    }

    public Set<String> requiredKeys() {
        return required;
    }

    /** Every key an operation of this kind may carry, including {@code op} and {@code request_id}. */
    public Set<String> allowedKeys() {
        Set<String> all = new HashSet<>(required);
        all.addAll(optional);
        all.add("op");
        all.add("request_id");
        return Set.copyOf(all);
    }
}
