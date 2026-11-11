package org.pgledger.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Source-text rules of the catalogue that bytecode cannot express. Each returns the problems found. */
public final class SourceRules {

    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*(//[^\\n]*\\s*)*\\}");
    private static final Pattern BROAD_CATCH = Pattern
            .compile("catch\\s*\\(\\s*(?:final\\s+)?(?:Exception|Throwable)\\s+\\w+\\s*\\)");
    private static final Pattern VENDOR = Pattern
            .compile("(?i)\\b(google|cloudflare|claude|codex|gemini|antigravity)\\b");
    private static final Pattern UTILITY_NAME = Pattern.compile(".*(Util|Utils|Helper|Manager)\\.java");

    private static final List<String> OS_TOKENS = List.of("os.name", "os.arch", "separatorChar", "File.separator",
            "pathSeparator", "EnabledOnOs", "DisabledOnOs", "EnabledIf(\"os", "condition.OS", "SystemUtils.IS_OS",
            "/proc/");

    private SourceRules() {
    }

    public static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ARC-008: no empty catch; no catch of Exception or Throwable except in the allow-listed files. */
    public static List<String> swallowedOrBroadCatches(Path mainJava, Set<String> allowedFileNames) {
        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(mainJava)) {
            String text = read(file);
            if (EMPTY_CATCH.matcher(text).find()) {
                problems.add(file + ": empty catch block");
            }
            if (BROAD_CATCH.matcher(text).find() && !allowedFileNames.contains(file.getFileName().toString())) {
                problems.add(file + ": catches Exception or Throwable");
            }
        }
        return problems;
    }

    /** ARC-032: no vendor, provider or cloud name in code. */
    public static List<String> vendorNames(Path mainJava) {
        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(mainJava)) {
            var matcher = VENDOR.matcher(read(file));
            if (matcher.find()) {
                problems.add(file + ": vendor name '" + matcher.group() + "'");
            }
        }
        return problems;
    }

    /**
     * ARC-033: Java does not need to know the operating system. No source reads the OS name, decides by the path
     * separator, switches a test by operating system or reads /proc. Paths, {@code Files.isSameFile},
     * {@code PathMatcher}, {@code ProcessHandle} and file system capabilities
     * ({@code supportedFileAttributeViews()}) replace them.
     */
    public static List<String> operatingSystemDetection(Path... roots) {
        List<String> problems = new ArrayList<>();
        for (Path root : roots) {
            for (Path file : javaFiles(root)) {
                String text = read(file);
                for (String token : OS_TOKENS) {
                    if (text.contains(token)) {
                        problems.add(file + ": operating system detection '" + token + "'");
                    }
                }
            }
        }
        return problems;
    }

    /** ARC-025: no class named like a static utility bucket. */
    public static List<String> utilityNames(Path mainJava) {
        return javaFiles(mainJava).stream().filter(p -> UTILITY_NAME.matcher(p.getFileName().toString()).matches())
                .map(p -> p + ": utility-style class name").toList();
    }

    /** ARC-002 (text part): build files carry no add-opens, preview or Lombok. */
    public static List<String> forbiddenBuildSwitches(List<Path> buildFiles) {
        List<String> problems = new ArrayList<>();
        for (Path file : buildFiles) {
            String text = read(file);
            for (String token : List.of("--add-opens", "--enable-preview", "lombok")) {
                if (text.contains(token)) {
                    problems.add(file + ": contains " + token);
                }
            }
        }
        return problems;
    }
}
