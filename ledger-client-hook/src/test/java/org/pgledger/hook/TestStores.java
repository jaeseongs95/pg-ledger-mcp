package org.pgledger.hook;

import java.nio.file.Path;
import org.pgledger.hook.keys.SessionKey;
import org.pgledger.hook.keys.SessionKeyStore;

/** How the tests open the key store of a folder and load a key: the one place that knows the shape of the store API. */
final class TestStores {

    private TestStores() {}

    static SessionKeyStore open(Path directory) {
        return new SessionKeyStore(directory);
    }

    static SessionKey load(SessionKeyStore store, String author) {
        return store.load(author);
    }
}
