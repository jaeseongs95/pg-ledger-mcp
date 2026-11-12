package org.pgledger.domain.rule;

import java.util.List;
import java.util.Objects;
import org.pgledger.domain.value.SessionKey;

/** The sessions that recorded a fix commit of one defect (facts for the transition policy). */
public record FixHistory(List<SessionKey> fixers) {

    public static final FixHistory NONE = new FixHistory(List.of());

    public FixHistory {
        fixers = List.copyOf(fixers);
    }

    public boolean hasFix() {
        return !fixers.isEmpty();
    }

    /** True when some fix was recorded by a session other than {@code actor}. */
    public boolean hasFixOtherThan(SessionKey actor) {
        return fixers.stream().anyMatch(fixer -> !Objects.equals(fixer, actor));
    }
}
