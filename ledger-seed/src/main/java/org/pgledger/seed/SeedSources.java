package org.pgledger.seed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The text files the seed bundle is built from (in {@code ledger-cli/src/seed}), read and checked: a bad line stops the build
 * with a message that names the file and the line, so a bundle is never made from a half-valid source.
 *
 * <ul>
 * <li>{@value #CATEGORIES}: tab separated, a header line and one line per code: {@code code}, the Korean and the English name,
 * the Korean and the English description. The sort order is the position in the file (10, 20, ...), the group is
 * {@code DEFECT_CATEGORY}, the state {@code ACTIVE}.</li>
 * <li>{@value #DEFAULTS}: {@code name=value} lines, {@code #} starts a comment line; the defaults of the installation (the
 * instance name, the ports).</li>
 * </ul>
 */
public final class SeedSources {

    public static final String CATEGORIES = "defect-categories.tsv";
    public static final String DEFAULTS = "install-defaults.properties";

    /** The distance between two sort orders, so that the owner can put a code between two of the seed. */
    static final int SORT_STEP = 10;

    private static final String HEADER = "code\tname_ko\tname_en\tdescription_ko\tdescription_en";
    private static final int COLUMNS = 5;
    private static final int MAX_NAME = 200;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_VALUE = 200;
    /** The format of a code of the ledger ({@code CodeValue}). */
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");
    private static final Pattern DEFAULT_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern CONTROL = Pattern.compile(".*\\p{Cntrl}.*");

    private SeedSources() {
    }

    /** The codes of the file, in its order. */
    public static List<SeedCode> codes(Path file) {
        List<String> lines = lines(file);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw problem(file, 1, "the first line must be the header: code, name_ko, name_en, description_ko, description_en "
                    + "separated by tabs");
        }
        List<SeedCode> codes = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            int number = index + 1;
            String[] cells = line.split("\t", -1);
            if (cells.length != COLUMNS) {
                throw problem(file, number, "expected " + COLUMNS + " columns separated by tabs but found " + cells.length);
            }
            for (String cell : cells) {
                if (cell.isBlank() || !cell.equals(cell.strip())) {
                    throw problem(file, number, "a column is empty or has white space around it");
                }
            }
            if (!CODE.matcher(cells[0]).matches()) {
                throw problem(file, number, "the code must be a letter, then letters, digits or underscores, at most 32");
            }
            if (cells[1].length() > MAX_NAME || cells[2].length() > MAX_NAME || cells[3].length() > MAX_DESCRIPTION
                    || cells[4].length() > MAX_DESCRIPTION) {
                throw problem(file, number, "a name is longer than " + MAX_NAME + " or a description longer than " + MAX_DESCRIPTION);
            }
            if (codes.stream().anyMatch(known -> known.code().equals(cells[0]))) {
                throw problem(file, number, "the code is there twice");
            }
            codes.add(new SeedCode(SeedSchema.CATEGORY_GROUP, cells[0], cells[1], cells[2], cells[3], cells[4],
                    SORT_STEP * (codes.size() + 1), SeedSchema.ACTIVE));
        }
        if (codes.isEmpty()) {
            throw problem(file, 1, "the file has no code");
        }
        return List.copyOf(codes);
    }

    /** The defaults of the file, in its order. */
    public static Map<String, String> defaults(Path file) {
        List<String> lines = lines(file);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int number = index + 1;
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw problem(file, number, "expected name=value");
            }
            String name = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if (!DEFAULT_NAME.matcher(name).matches()) {
                throw problem(file, number, "the name must be lower case letters, digits and hyphens, a letter first");
            }
            if (value.isBlank() || !value.equals(value.strip()) || value.length() > MAX_VALUE
                    || CONTROL.matcher(value).matches()) {
                throw problem(file, number, "the value is empty, has white space around it, holds a control character or is "
                        + "longer than " + MAX_VALUE);
            }
            if (defaults.putIfAbsent(name, value) != null) {
                throw problem(file, number, "the name is there twice");
            }
        }
        if (defaults.isEmpty()) {
            throw problem(file, 1, "the file has no default");
        }
        return Collections.unmodifiableMap(defaults);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SeedUnavailableException("the seed source " + file + " cannot be read as UTF-8 text: " + e, e);
        }
    }

    private static SeedUnavailableException problem(Path file, int line, String text) {
        return new SeedUnavailableException(file.getFileName() + ":" + line + ": " + text);
    }
}
