package org.pgledger.hook.keys;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where the session keys live: a {@link Usable} absolute folder, or a reason that there is none ({@link Unconfigured},
 * {@link Rejected}). Resolving never throws: an unusable folder is a value, and every {@link SessionKeyStore#load} of a
 * store on it refuses with {@link SessionKeyException.Reason#FOLDER_UNAVAILABLE}, so the hook can answer the host (a
 * relative path would put the keys wherever the host happens to start the hook).
 */
public sealed interface KeyFolder {

    /** The environment variable that names the key folder. */
    String ENVIRONMENT_VARIABLE = "LEDGER_HOOK_KEY_DIR";

    /** The key folder inside the user's home directory. */
    List<String> DEFAULT_IN_HOME = List.of(".pg-ledger-mcp", "session-keys");

    /** An absolute folder; the keys are kept in it. */
    record Usable(Path directory) implements KeyFolder {

        public Usable {
            Objects.requireNonNull(directory, "directory");
            if (!directory.isAbsolute()) {
                throw new IllegalArgumentException("the key folder must be an absolute path");
            }
        }
    }

    /** No folder can be named at all (no variable and no home folder). */
    record Unconfigured() implements KeyFolder {}

    /**
     * A folder that is named but not usable: a text that is no path, or a relative path.
     *
     * @param cause why (it names the configured text, never key material)
     */
    record Rejected(Exception cause) implements KeyFolder {

        public Rejected {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * The folder named by the environment variable if it is set and not blank, otherwise
     * {@code <home>/.pg-ledger-mcp/session-keys}. Without either, no folder is named.
     *
     * @param environmentValue the value of {@link #ENVIRONMENT_VARIABLE}, {@code null} if it is not set
     * @param homeValue        the value of the system property {@code user.home}, {@code null} if it is not set
     */
    static KeyFolder resolve(String environmentValue, String homeValue) {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return of(environmentValue, List.of());
        }
        if (homeValue != null && !homeValue.isBlank()) {
            return of(homeValue, DEFAULT_IN_HOME);
        }
        return new Unconfigured();
    }

    /** The folder of this path: usable if it is absolute, otherwise rejected. */
    static KeyFolder of(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return directory.isAbsolute() ? new Usable(directory)
                : new Rejected(new IllegalArgumentException("the key folder must be an absolute path"));
    }

    /**
     * The folder of path text, the arguments of {@link Path#of(String, String...)}, for the caller that has no file
     * system API of its own. A blank text names no folder; a text that is no path is rejected.
     */
    static KeyFolder of(String first, List<String> more) {
        if (first == null || first.isBlank()) {
            return new Unconfigured();
        }
        try {
            return of(Path.of(first, more.toArray(String[]::new)));
        } catch (InvalidPathException invalid) {
            return new Rejected(invalid);
        }
    }
}
