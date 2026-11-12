package org.pgledger.database.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * One schema file {@code V{n}__{description}.sql}. The text is normalised to LF line ends before it is hashed and
 * applied, so a checkout with CRLF line ends gives the same digest.
 */
public record MigrationFile(int version, String fileName, String text, Sha256 digest) {

    private static final Pattern NAME = Pattern.compile("V([1-9][0-9]*)__([a-z0-9_]+)\\.sql");

    /**
     * All files at {@code location}, ordered by version. A file with another name is an error, not skipped.
     *
     * @throws InfrastructureException SCHEMA_FILE_NAME_INVALID, SCHEMA_VERSION_DUPLICATE or SCHEMA_FILES_UNREADABLE
     */
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
                    throw new InfrastructureException(InfrastructureCode.SCHEMA_FILE_NAME_INVALID, List.of(name));
                }
                String text = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                MigrationFile file = new MigrationFile(Integer.parseInt(matcher.group(1)), name, text,
                        Sha256.ofText(text));
                if (byVersion.put(file.version(), file) != null) {
                    throw new InfrastructureException(InfrastructureCode.SCHEMA_VERSION_DUPLICATE,
                            List.of(String.valueOf(file.version())));
                }
            }
            return List.copyOf(new ArrayList<>(byVersion.values()));
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_FILES_UNREADABLE, List.of(location), e);
        }
    }

    /**
     * The versions must run 1, 2, 3 ... without a gap.
     *
     * @throws InfrastructureException SCHEMA_VERSION_GAP
     */
    public static void requireContiguous(List<MigrationFile> files) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).version() != i + 1) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_VERSION_GAP,
                        List.of(String.valueOf(i + 1), String.valueOf(files.get(i).version())));
            }
        }
    }
}
