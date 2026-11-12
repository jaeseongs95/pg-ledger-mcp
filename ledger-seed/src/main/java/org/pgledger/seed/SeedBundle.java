package org.pgledger.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The seed bundle: the initial data of a new installation, the default common codes and the defaults of the installation, as
 * the build wrote them into one H2 file ({@link SeedBuilder}). The bundle is read once, on the first question, and then lives
 * in memory; nothing is written to it. H2 only gives the data its format: the data is moved into the ledger database, which is
 * always a DBMS that the operator installs, by {@code ledger-cli setup}.
 */
public final class SeedBundle {

    /** The name of the bundle file, and of the class path resource it is packed as. */
    public static final String RESOURCE = "ledger-seed.mv.db";

    private record Content(List<SeedCode> codes, Map<String, String> defaults) {
    }

    private final Supplier<Content> source;
    private Content content;

    private SeedBundle(Supplier<Content> source) {
        this.source = source;
    }

    /** The bundle that is packed with the program: the resource {@value #RESOURCE} of the class path. */
    public static SeedBundle packaged() {
        return new SeedBundle(SeedBundle::readPackaged);
    }

    /** The bundle in a file of the file system (the build output, a test). */
    public static SeedBundle ofFile(Path file) {
        return new SeedBundle(() -> read(file));
    }

    /** The codes of the seed, in their sort order. */
    public synchronized List<SeedCode> codes() {
        return content().codes();
    }

    /** The defaults of the installation by name. */
    public synchronized Map<String, String> installDefaults() {
        return content().defaults();
    }

    /**
     * One default of the installation.
     *
     * @throws SeedUnavailableException when the bundle has no such default
     */
    public String installDefault(String name) {
        String value = installDefaults().get(name);
        if (value == null) {
            throw new SeedUnavailableException("the seed bundle has no install default named " + name);
        }
        return value;
    }

    /** The digest of the codes ({@link SeedDigest}). */
    public String digest() {
        return SeedDigest.of(codes());
    }

    private Content content() {
        if (content == null) {
            content = source.get();
        }
        return content;
    }

    /** The resource is copied to a temporary directory (H2 opens a file, not a jar entry) that is removed again. */
    private static Content readPackaged() {
        try (InputStream stream = SeedBundle.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new SeedUnavailableException("the seed bundle " + RESOURCE + " is not on the class path of the program");
            }
            Path directory = Files.createTempDirectory("ledger-seed");
            try {
                Path file = directory.resolve(RESOURCE);
                Files.copy(stream, file);
                return read(file);
            } finally {
                remove(directory);
            }
        } catch (IOException e) {
            throw new SeedUnavailableException("the seed bundle cannot be copied for reading: " + e.getMessage(), e);
        }
    }

    private static Content read(Path file) {
        try (Connection connection = SeedDatabase.open(file, "ACCESS_MODE_DATA=r;IFEXISTS=TRUE");
                Statement statement = connection.createStatement()) {
            List<SeedCode> codes = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(SeedSchema.CODE_SELECT)) {
                while (rows.next()) {
                    codes.add(new SeedCode(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), rows.getString(6), rows.getInt(7), rows.getString(8)));
                }
            }
            Map<String, String> defaults = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery(SeedSchema.DEFAULT_SELECT)) {
                while (rows.next()) {
                    defaults.put(rows.getString(1), rows.getString(2));
                }
            }
            return new Content(List.copyOf(codes), Collections.unmodifiableMap(defaults));
        } catch (SQLException e) {
            throw new SeedUnavailableException("the seed bundle " + file + " cannot be read: " + e.getMessage(), e);
        }
    }

    private static void remove(Path directory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(directory);
    }
}
