package org.pgledger.hook;

import java.nio.file.Path;
import java.util.List;
import org.pgledger.hook.keys.SessionKey;
import org.pgledger.hook.keys.SessionKeyStore;

/** How the tests open the key store of a folder and load a key: the one place that knows the shape of the store API. */
final class TestStores {

    /** The account that owns the key folder: what {@code Main} passes from {@code user.name}. */
    static final String USER = System.getProperty("user.name");

    private TestStores() {}

    static SessionKeyStore open(Path directory) {
        return new SessionKeyStore(directory, USER);
    }

    /** A store for folder text as the environment variable gives it (it may be relative: the store refuses that). */
    static SessionKeyStore at(String first, List<String> more) {
        return SessionKeyStore.at(first, more, USER);
    }

    static SessionKey load(SessionKeyStore store, String server, String author) {
        return store.load(server, author);
    }
}
