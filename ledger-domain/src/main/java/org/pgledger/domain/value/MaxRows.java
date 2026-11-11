package org.pgledger.domain.value;

import org.pgledger.domain.error.ValidationException;

/** Row limit of a list tool: 1..1000, default 50. */
public record MaxRows(int value) {

    public static final MaxRows DEFAULT = new MaxRows(50);
    public static final int MIN = 1;
    public static final int MAX = 1000;

    public MaxRows {
        if (value < MIN || value > MAX) {
            throw ValidationException.invalid("max_rows", "must be " + MIN + ".." + MAX);
        }
    }

    /** {@code null} gives the default; a number with a fraction is rejected. */
    public static MaxRows of(Number raw) {
        if (raw == null) {
            return DEFAULT;
        }
        if (raw.doubleValue() != Math.rint(raw.doubleValue())) {
            throw ValidationException.invalid("max_rows", "must be an integer");
        }
        long whole = raw.longValue();
        if (whole < MIN || whole > MAX) {
            throw ValidationException.invalid("max_rows", "must be " + MIN + ".." + MAX);
        }
        return new MaxRows((int) whole);
    }
}
