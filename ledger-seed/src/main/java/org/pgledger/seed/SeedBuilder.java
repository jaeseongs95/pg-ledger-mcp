package org.pgledger.seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Writes the seed bundle {@value SeedBundle#RESOURCE} from the text files of the seed ({@link SeedSources}). The build runs
 * it as a Java program on the JVM of the build ({@code generateSeed} of {@code ledger-cli}), so the binary file is made from
 * the text and never committed. A bad source file stops it before any file is written; an existing bundle of the output
 * directory is replaced, never extended.
 */
public final class SeedBuilder {

    private SeedBuilder() {
    }

    /** {@code SeedBuilder <directory of the seed sources> <output directory>}. */
    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: SeedBuilder <directory of the seed sources> <output directory>");
        }
        build(Path.of(arguments[0]), Path.of(arguments[1]));
    }

    /**
     * Reads the sources, checks them and writes the bundle.
     *
     * @return the bundle file
     * @throws SeedUnavailableException when a source is bad (naming file and line) or the file cannot be written
     */
    public static Path build(Path sources, Path output) {
        List<SeedCode> codes = SeedSources.codes(sources.resolve(SeedSources.CATEGORIES));
        Map<String, String> defaults = SeedSources.defaults(sources.resolve(SeedSources.DEFAULTS));
        Path file = output.resolve(SeedBundle.RESOURCE);
        try {
            Files.createDirectories(output);
            Files.deleteIfExists(file);
            try (Connection connection = SeedDatabase.open(file, "")) {
                create(connection);
                insert(connection, codes, defaults);
            }
        } catch (IOException | SQLException e) {
            throw new SeedUnavailableException("the seed bundle " + file + " cannot be written: " + e.getMessage(), e);
        }
        return file;
    }

    private static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String definition : SeedSchema.DEFINITIONS) {
                statement.execute(definition);
            }
        }
    }

    private static void insert(Connection connection, List<SeedCode> codes, Map<String, String> defaults) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SeedSchema.CODE_INSERT)) {
            for (SeedCode code : codes) {
                statement.setString(1, code.group());
                statement.setString(2, code.code());
                statement.setString(3, code.nameKo());
                statement.setString(4, code.nameEn());
                statement.setString(5, code.descriptionKo());
                statement.setString(6, code.descriptionEn());
                statement.setInt(7, code.sortOrder());
                statement.setString(8, code.state());
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(SeedSchema.DEFAULT_INSERT)) {
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.executeUpdate();
            }
        }
    }
}
