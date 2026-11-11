package org.pgledger.mcp.archfixture;

/**
 * A class that does what the security package must never do: it compares a token with {@code String.equals}. It
 * exists only so that the architecture rule against that comparison can be shown to fail.
 */
public final class LeakyTokenCheck {

    private final String expected;

    public LeakyTokenCheck(String expected) {
        this.expected = expected;
    }

    public boolean accepts(String presented) {
        return expected.equals(presented);
    }
}
