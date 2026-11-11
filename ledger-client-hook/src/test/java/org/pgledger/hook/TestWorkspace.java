package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The files of one test: a temporary root with an empty {@code work} folder (stdin, stdout and working directories of a
 * process test) and the path of the {@code keys} folder, which is NOT created: the hook creates it with its first key.
 * Nothing of the real home folder is involved; {@link #close()} deletes the whole root when the test ends.
 */
final class TestWorkspace implements AutoCloseable {

    private final Path root;

    TestWorkspace() {
        try {
            root = Files.createTempDirectory("ledger-hook-test");
            Files.createDirectory(root.resolve("work"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path root() {
        return root;
    }

    Path work() {
        return root.resolve("work");
    }

    /** The key folder (LEDGER_HOOK_KEY_DIR of a process test); absent until the first key is created. */
    Path keys() {
        return root.resolve("keys");
    }

    @Override
    public void close() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        }
    }
}
