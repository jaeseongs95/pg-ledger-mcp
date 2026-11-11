package org.pgledger.domain.rule;

import java.util.Arrays;
import java.util.Objects;
import org.pgledger.domain.value.Token;

/**
 * The structural kinds of link. Any other kind token names a relation between two defects; the allowed relation
 * kinds are data (common codes of group RELATION_KIND), so they are not constants here.
 */
public enum LinkKind implements Token {
    DEFECT_TASK("defect_task"), CONDITION_TASK("condition_task"), CONDITION_DEFECT("condition_defect"),
    RELATION("relation");

    private final String token;

    LinkKind(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /** The structural kind of a wire token; every token that is not structural is a relation kind code. */
    public static LinkKind ofToken(String wireToken) {
        return Arrays.stream(values())
                .filter(kind -> kind != RELATION && Objects.equals(kind.token, wireToken))
                .findFirst()
                .orElse(RELATION);
    }
}
