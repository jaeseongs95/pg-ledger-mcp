package org.pgledger.hook;

import java.util.List;

/**
 * Where the session keys live, as the arguments of {@code Path.of(first, more...)}: the file system API itself is used
 * in the {@code keys} package only. A pure function of the two values that {@link Main} reads from the process.
 *
 * @param first the first path element ({@code ""} if no folder can be named at all)
 * @param more  the remaining path elements
 */
record KeyFolder(String first, List<String> more) {

    /** The environment variable that names the key folder. */
    static final String ENVIRONMENT_VARIABLE = "LEDGER_HOOK_KEY_DIR";

    /** The key folder inside the user's home directory. */
    static final List<String> DEFAULT_IN_HOME = List.of(".pg-ledger-mcp", "session-keys");

    /**
     * The folder named by the environment variable if it is set and not blank, otherwise
     * {@code <home>/.pg-ledger-mcp/session-keys}. Without either, the folder is empty and no key can be used.
     *
     * @param environmentValue the value of {@link #ENVIRONMENT_VARIABLE}, {@code null} if it is not set
     * @param homeValue        the value of the system property {@code user.home}, {@code null} if it is not set
     */
    static KeyFolder resolve(String environmentValue, String homeValue) {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return new KeyFolder(environmentValue, List.of());
        }
        if (homeValue != null && !homeValue.isBlank()) {
            return new KeyFolder(homeValue, DEFAULT_IN_HOME);
        }
        return new KeyFolder("", List.of());
    }
}
