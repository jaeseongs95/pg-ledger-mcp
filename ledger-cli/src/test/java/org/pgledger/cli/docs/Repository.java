package org.pgledger.cli.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The files of the repository that the documentation tests read. The root of the repository is passed in by the Gradle
 * test task of this module (system property {@code ledger.docs.root}), never guessed from the working directory.
 */
final class Repository {

    private static final Path ROOT = Path.of(Objects.requireNonNull(System.getProperty("ledger.docs.root"),
            "ledger.docs.root is set by the test task of ledger-cli"));

    private Repository() {
    }

    static Path resolve(String relative) {
        return ROOT.resolve(relative);
    }

    static boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    static boolean isDirectory(String relative) {
        return Files.isDirectory(resolve(relative));
    }

    /** The text of a file; a missing file fails the test with its name. */
    static String read(String relative) {
        return read(resolve(relative));
    }

    static String read(Path file) {
        assertThat(file).as("%s exists", file).isRegularFile();
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " cannot be read", e);
        }
    }

    /** The regular files below a directory whose name ends with the suffix, in path order. */
    static List<Path> files(String directory, String suffix) {
        try (Stream<Path> walk = Files.walk(resolve(directory))) {
            return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(directory + " cannot be listed", e);
        }
    }

    /** The names of the directories directly below a directory. */
    static List<String> subdirectories(String directory) {
        try (Stream<Path> list = Files.list(resolve(directory))) {
            return list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(directory + " cannot be listed", e);
        }
    }

    /** The names of the files directly below a directory; none when the directory does not exist (yet). */
    static List<String> filesDirectlyIn(String directory) {
        if (!isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(resolve(directory))) {
            return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(directory + " cannot be listed", e);
        }
    }
}
