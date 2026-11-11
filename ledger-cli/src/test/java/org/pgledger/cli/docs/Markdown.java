package org.pgledger.cli.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Markdown text as the documentation tests read it: lines outside fenced code blocks (prose), the fenced blocks,
 * headings, pipe tables, code spans and links. Only the forms that the documents of this repository use are understood.
 */
record Markdown(List<String> lines) {

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");

    static Markdown of(String text) {
        return new Markdown(text.lines().toList());
    }

    private static boolean fence(String line) {
        return line.strip().startsWith("```");
    }

    String text() {
        return String.join("\n", lines);
    }

    /** The lines outside fenced code blocks (the fence lines themselves are not part of it). */
    List<String> prose() {
        List<String> prose = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (fence(line)) {
                inside = !inside;
            } else if (!inside) {
                prose.add(line);
            }
        }
        return prose;
    }

    /** The content of every fenced code block, in order. */
    List<List<String>> fences() {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : lines) {
            if (fence(line)) {
                if (current == null) {
                    current = new ArrayList<>();
                } else {
                    blocks.add(current);
                    current = null;
                }
            } else if (current != null) {
                current.add(line);
            }
        }
        return blocks;
    }

    /** The texts of the headings of one level ({@code ##} is level 2), outside code blocks. */
    List<String> headings(int level) {
        String prefix = "#".repeat(level) + " ";
        return prose().stream().filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length()).strip())
                .toList();
    }

    /** The lines below the level-2 heading with this text, up to the next level-2 heading (empty when it is absent). */
    Markdown part(String heading) {
        List<String> part = new ArrayList<>();
        String wanted = "## " + heading;
        boolean inside = false;
        boolean collecting = false;
        for (String line : lines) {
            if (fence(line)) {
                inside = !inside;
            } else if (!inside && line.startsWith("## ")) {
                collecting = wanted.equals(line.stripTrailing());
                continue;
            }
            if (collecting) {
                part.add(line);
            }
        }
        return new Markdown(part);
    }

    /**
     * The lines under each level-3 heading of this text: heading text, then the prose lines that are not blank (code
     * blocks are not part of it).
     */
    List<Subsection> subsections() {
        List<Subsection> sections = new ArrayList<>();
        String heading = null;
        List<String> body = new ArrayList<>();
        for (String line : prose()) {
            if (line.startsWith("### ")) {
                if (heading != null) {
                    sections.add(new Subsection(heading, List.copyOf(body)));
                }
                heading = line.substring(4).strip();
                body = new ArrayList<>();
            } else if (heading != null && !line.isBlank()) {
                body.add(line);
            }
        }
        if (heading != null) {
            sections.add(new Subsection(heading, List.copyOf(body)));
        }
        return sections;
    }

    /** The texts inside single backticks in the prose, without duplicates, in sorted order. */
    Set<String> codeSpans() {
        Set<String> spans = new TreeSet<>();
        Matcher matcher = CODE_SPAN.matcher(String.join("\n", prose()));
        while (matcher.find()) {
            spans.add(matcher.group(1));
        }
        return spans;
    }

    /** The targets of the links of the prose. */
    List<String> links() {
        List<String> links = new ArrayList<>();
        Matcher matcher = LINK.matcher(String.join("\n", prose()));
        while (matcher.find()) {
            links.add(matcher.group(1));
        }
        return links;
    }

    /** The pipe tables of the prose: a run of lines that start with a pipe; the second line is the separator. */
    List<Table> tables() {
        List<Table> tables = new ArrayList<>();
        List<String> run = new ArrayList<>();
        for (String line : prose()) {
            if (line.strip().startsWith("|")) {
                run.add(line);
            } else {
                addTable(tables, run);
                run = new ArrayList<>();
            }
        }
        addTable(tables, run);
        return tables;
    }

    private static void addTable(List<Table> tables, List<String> run) {
        if (run.size() >= 2) {
            tables.add(new Table(cells(run.getFirst()), run.stream().skip(2).map(Markdown::cells).toList()));
        }
    }

    /** The cells of a table line; a pipe inside a code span does not end a cell. */
    static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        for (char c : line.strip().toCharArray()) {
            if (c == '`') {
                code = !code;
            }
            if (c == '|' && !code) {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().strip());
        // the line starts and ends with a pipe: the pieces before the first and after the last one are empty
        return cells.subList(1, cells.size() - 1);
    }

    boolean hasHangul() {
        return text().codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
    }

    /** One level-3 section: its heading and the non-blank prose lines under it. */
    record Subsection(String heading, List<String> body) {
    }

    /** A pipe table: the header cells and the cells of every row after the separator. */
    record Table(List<String> header, List<List<String>> rows) {

        String firstHeader() {
            return header.getFirst();
        }

        /** The first cell of every row, with the backticks around it removed. */
        List<String> firstCellNames() {
            return rows.stream().map(row -> unwrap(row.getFirst())).toList();
        }

        /** The row whose first cell is this name (in backticks or not), or null. */
        List<String> rowOf(String name) {
            return rows.stream().filter(row -> name.equals(unwrap(row.getFirst()))).findFirst().orElse(null);
        }

        static String unwrap(String cell) {
            return cell.length() > 1 && cell.startsWith("`") && cell.endsWith("`") ? cell.substring(1, cell.length() - 1)
                    : cell;
        }
    }
}
