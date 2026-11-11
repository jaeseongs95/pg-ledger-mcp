package org.pgledger.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Source scan for the catch and string rules of the development principles (3.3, 3.4). Comments are dropped and
 * the content of every string literal is blanked ({@code ""}) before the patterns run, so a pattern never matches
 * the text of a literal (this file included). The same scan sits in the tests of the database, cli and hook
 * modules; the shared test fixtures belong to another owner.
 */
final class CodingScan {

    /** Text block, string, char, block comment or line comment, in the order a Java lexer meets them. */
    private static final Pattern LEXEME = Pattern.compile(
            "\"\"\"(?s:.*?)\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'|/\\*(?s:.*?)\\*/|//[^\\n]*");
    private static final Pattern CATCH = Pattern.compile("catch\\s*\\(([^)]*)\\)\\s*\\{(\\s*\\})?");
    private static final Set<String> BROAD = Set.of("Exception", "Throwable", "RuntimeException", "Error");
    private static final Pattern STRING_EQUALS_ARGUMENT = Pattern.compile("[\\w)\\]]\\s*\\.\\s*equals(?:IgnoreCase)?\\s*\\(\\s*\"\"");
    private static final Pattern STRING_IDENTITY = Pattern.compile("[=!]=\\s*\"\"|\"\"\\s*[=!]=");
    private static final Pattern NEW_STRING = Pattern.compile("new\\s+String\\s*\\(");
    private static final Pattern INTERN = Pattern.compile("\\.\\s*intern\\s*\\(\\s*\\)");
    private static final Pattern STRING_CASE = Pattern.compile("\\bcase\\s+\"\"");

    private CodingScan() {
    }

    /**
     * ARC-036: no empty catch, no catch of SQLException (outside a directory named {@code sqlAllowedDirectory}, the
     * driver boundary) and no catch of Exception, Throwable, RuntimeException or Error outside the named boundary files.
     */
    static List<String> catchProblems(Set<String> broadAllowedFiles, String sqlAllowedDirectory, Path... roots) {
        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(roots)) {
            String text = strip(file);
            Matcher matcher = CATCH.matcher(text);
            while (matcher.find()) {
                if (matcher.group(2) != null) {
                    problems.add(file + ": empty catch block");
                }
                for (String type : matcher.group(1).split("\\|")) {
                    String name = simpleName(type);
                    if (BROAD.contains(name) && !broadAllowedFiles.contains(file.getFileName().toString())) {
                        problems.add(file + ": catches " + name);
                    }
                    if ("SQLException".equals(name) && !inDirectory(file, sqlAllowedDirectory)) {
                        problems.add(file + ": catches SQLException (catch the Spring DataAccessException hierarchy)");
                    }
                }
            }
        }
        return problems;
    }

    /**
     * ARC-037: no {@code variable.equals("literal")}, no {@code ==} against a string literal, no string
     * {@code switch}, no {@code intern()}, and no {@code new String(} outside the named files.
     */
    static List<String> stringProblems(Set<String> newStringAllowedFiles, Path... roots) {
        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(roots)) {
            String text = strip(file);
            report(problems, file, STRING_EQUALS_ARGUMENT.matcher(text), "variable.equals(literal): write \"literal\".equals(variable)");
            report(problems, file, STRING_IDENTITY.matcher(text), "string compared with == or !=");
            report(problems, file, STRING_CASE.matcher(text), "switch on a string");
            report(problems, file, INTERN.matcher(text), "intern()");
            if (!newStringAllowedFiles.contains(file.getFileName().toString())) {
                report(problems, file, NEW_STRING.matcher(text), "new String(...)");
            }
        }
        return problems;
    }

    private static void report(List<String> problems, Path file, Matcher matcher, String what) {
        if (matcher.find()) {
            problems.add(file + ": " + what);
        }
    }

    private static String simpleName(String type) {
        String trimmed = type.strip();
        String last = trimmed.substring(trimmed.lastIndexOf(' ') + 1);
        return last.substring(last.lastIndexOf('.') + 1);
    }

    private static boolean inDirectory(Path file, String directory) {
        return directory != null && file.getParent() != null && file.getParent().endsWith(directory);
    }

    private static String strip(Path file) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher matcher = LEXEME.matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String lexeme = matcher.group();
            boolean comment = lexeme.startsWith("/*") || lexeme.startsWith("//");
            matcher.appendReplacement(out, comment ? " " : Matcher.quoteReplacement(lexeme.startsWith("'") ? "'c'" : "\"\""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static List<Path> javaFiles(Path... roots) {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".java")).sorted()
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }
}
