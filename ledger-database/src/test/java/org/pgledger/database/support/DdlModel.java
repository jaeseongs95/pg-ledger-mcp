package org.pgledger.database.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The tables, constraints and indexes of ANSI DDL text written in the style of the schema files (comments and literals
 * already removed): enough structure to check identifiers, column types, keys and CHECK clauses.
 */
public record DdlModel(List<Table> tables, List<Index> indexes) {

    public record Column(String name, String type, boolean notNull) {
    }

    /** {@code kind} is PRIMARY KEY, UNIQUE, FOREIGN KEY or CHECK; {@code body} is the text after it. */
    public record Constraint(String name, String kind, String body) {

        /** The column names inside the first parentheses of a key. */
        public List<String> columns() {
            String inner = body.substring(body.indexOf('(') + 1, body.indexOf(')'));
            return Arrays.stream(inner.split(",")).map(String::trim).toList();
        }
    }

    public record Table(String name, List<Column> columns, List<Constraint> constraints) {
    }

    public record Index(String name, String table, List<String> columns) {
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TABLE =Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*$");
    private static final Pattern INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*$");
    private static final Pattern CONSTRAINT = Pattern.compile(
            "(?is)^CONSTRAINT\\s+(\\w+)\\s+(PRIMARY\\s+KEY|UNIQUE|FOREIGN\\s+KEY|CHECK)\\s*(.*)$");
    private static final Pattern COLUMN = Pattern.compile("(?is)^(\\w+)\\s+([A-Za-z]+(?:\\(\\d+(?:,\\d+)?\\))?)(.*)$");

    public static DdlModel parse(String strippedSql) {
        List<Table> tables = new ArrayList<>();
        List<Index> indexes = new ArrayList<>();
        Arrays.stream(strippedSql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).forEach(statement -> {
            Matcher table = TABLE.matcher(statement);
            Matcher index = INDEX.matcher(statement);
            if (table.matches()) {
                tables.add(table(table.group(1), table.group(2)));
            } else if (index.matches()) {
                indexes.add(new Index(index.group(1), index.group(2),
                        Arrays.stream(index.group(3).split(",")).map(String::strip).toList()));
            }
        });
        return new DdlModel(tables, indexes);
    }

    private static Table table(String name, String body) {
        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        topLevelElements(body).forEach(element -> {
            Matcher constraint = CONSTRAINT.matcher(element);
            Matcher column = COLUMN.matcher(element);
            if (constraint.matches()) {
                constraints.add(new Constraint(constraint.group(1), WHITESPACE.matcher(constraint.group(2)).replaceAll(" ")
                        .toUpperCase(), constraint.group(3)));
            } else if (column.matches()) {
                columns.add(new Column(column.group(1), column.group(2).toUpperCase(),
                        column.group(3).toUpperCase().contains("NOT NULL")));
            }
        });
        return new Table(name, columns, constraints);
    }

    /** The comma separated elements of a table body, not splitting inside parentheses. */
    private static List<String> topLevelElements(String body) {
        List<String> elements = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (c == ',' && depth == 0) {
                elements.add(body.substring(start, i).strip());
                start = i + 1;
            }
        }
        elements.add(body.substring(start).strip());
        return elements;
    }

    /** Every name a schema file defines: tables, columns, constraints and indexes. */
    public List<String> identifiers() {
        return Stream.concat(tables.stream().flatMap(t -> Stream.concat(Stream.of(t.name()), Stream.concat(
                t.columns().stream().map(Column::name), t.constraints().stream().map(Constraint::name)))),
                indexes.stream().map(Index::name)).toList();
    }
}
