package org.pgledger.hook.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HK-044 (creation part): the key file is created without a race. The loser of the {@code CREATE_NEW} race must never
 * overwrite the file of the winner, so {@link KeyFileWriter} answers {@code false} for a file that is there and leaves
 * its bytes as they are. (The key store reads the winner's file then; see the key store tests.)
 */
class KeyFileWriterTest {

    private static final KeyFileWriter WRITER = new KeyFileWriter(new Protection.Unprotected());

    @Test
    @DisplayName("HK-044 a file that does not exist is created with the given bytes: the answer is true")
    void createsANewFile(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("new.key");
        byte[] content = {1, 2, 3, 4};
        assertThat(WRITER.createNew(file, content)).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo(content);
    }

    @Test
    @DisplayName("HK-044 a file that already exists (another process won the race) is not touched: the answer is false and its bytes stay")
    void neverOverwritesTheWinner(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("winner.key");
        byte[] winner = {9, 9, 9, 9, 9, 9};
        Files.write(file, winner);
        assertThat(WRITER.createNew(file, new byte[] {1, 2})).isFalse();
        assertThat(Files.readAllBytes(file)).as("the winner's file").isEqualTo(winner);
    }

    @Test
    @DisplayName("HK-044 two creations of the same file in a row: the first wins, the second is the loser and changes nothing")
    void secondCreationLoses(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("race.key");
        byte[] first = {1, 1, 1};
        assertThat(WRITER.createNew(file, first)).isTrue();
        assertThat(WRITER.createNew(file, new byte[] {2, 2, 2, 2, 2})).isFalse();
        assertThat(Files.readAllBytes(file)).isEqualTo(first);
    }
}
