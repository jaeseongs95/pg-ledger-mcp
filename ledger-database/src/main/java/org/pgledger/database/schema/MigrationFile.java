package org.pgledger.database.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.value.Sha256;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * One schema file {@code V{n}__{description}.sql}. The text is normalised to LF line ends before it is hashed and
 * applied, so a checkout with CRLF line ends gives the same digest.
 */
public record MigrationFile(int version, String fileName, String text, Sha256 digest) {

    private static final Pattern NAME = Pattern.compile("V([1-9][0-9]*)__([a-z0-9_]+)\\.sql");

    /** All files at {@code location}, ordered by version. A file with another name is an error, not skipped. */
    public static List<MigrationFile> load(ResourcePatternResolver resolver, String location) {
        try {
            Map<Integer, MigrationFile> byVersion = new TreeMap<>();
            for (Resource resource : resolver.getResources(location + "*")) {
                String name = resource.getFilename();
                if (name == null || !resource.isReadable() || resource.contentLength() == 0 && !name.contains(".")) {
                    continue;
                }
                Matcher matcher = NAME.matcher(name);
                if (!matcher.matches()) {
                    throw new SchemaMigrationException("bad schema file name '" + name
                            + "': expected V<number>__<description>.sql (lower case, number without leading zero)");
                }
                String text = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
                MigrationFile file = new MigrationFile(Integer.parseInt(matcher.group(1)), name, text,
                        Sha256.ofText(text));
                if (byVersion.put(file.version(), file) != null) {
                    throw new SchemaMigrationException("two schema files for version " + file.version());
                }
            }
            return List.copyOf(new ArrayList<>(byVersion.values()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the schema files at " + location, e);
        }
    }

    /** The versions must run 1, 2, 3 ... without a gap. */
    public static void requireContiguous(List<MigrationFile> files) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).version() != i + 1) {
                throw new SchemaMigrationException("schema files have a gap: expected version " + (i + 1)
                        + " but found V" + files.get(i).version());
            }
        }
    }
}
