package org.pgledger.testing;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The architecture rules of the development principles that every module holds for its production code (ARC-PRN-01 ..
 * ARC-PRN-06, docs/testing/TEST_CASES.md section 25): one place per rule. A rule answers its {@link Finding}s; a module test
 * names its allow list (rule id, key, reason) and asks {@link #problems}: a finding that is not allowed, an allowed key that
 * has no reason and an allowed key that no finding uses (a list that outlives the code it excused) are all problems.
 *
 * <p>The bytecode rules see what ArchUnit sees: a call that is in the class files. A call through an interface, or a lambda
 * is not followed (the calls of a lambda body are attributed to the method that holds it). The source rules read the main sources through {@link CodingScan#strip}, so comments and the content of
 * string literals are never looked at.
 */
public final class PrincipleRules {

    public static final String BEAN_OF_OWN_TYPE = "ARC-PRN-01";
    public static final String OPTIONAL_STORED = "ARC-PRN-02";
    public static final String OPTIONAL_GET = "ARC-PRN-03";
    public static final String OR_ELSE_NULL = "ARC-PRN-04";
    public static final String FILE_WRITE_IN_TRANSACTION = "ARC-PRN-05";
    public static final String VARIABLE_EQUALS = "ARC-PRN-06";

    private static final String OWN_PACKAGE = "org.pgledger.";
    private static final String BEAN = "org.springframework.context.annotation.Bean";
    private static final Set<String> TRANSACTIONAL = Set.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    private static final Set<String> FILE_WRITES = Set.of("write", "writeString", "newOutputStream", "newBufferedWriter",
            "copy", "move", "delete", "deleteIfExists", "createFile", "createDirectory", "createDirectories",
            "createTempFile", "createTempDirectory", "createLink", "createSymbolicLink", "setAttribute",
            "setPosixFilePermissions", "setLastModifiedTime", "setOwner");
    private static final Set<String> FILE_WRITING_CONSTRUCTORS = Set.of("java.io.FileOutputStream", "java.io.FileWriter");
    private static final Pattern OR_ELSE_NULL_CALL = Pattern.compile("\\.\\s*orElse\\s*\\(\\s*null\\s*\\)");
    /** {@code equals} only: {@code equalsIgnoreCase} has no null-safe static form, so two variables are compared with it. */
    private static final Pattern EQUALS_CALL = Pattern.compile("\\.\\s*equals\\s*\\(");
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private PrincipleRules() {
    }

    /**
     * One thing a rule found.
     *
     * @param key     what an allow list names it by: {@code Class.member} for the bytecode rules (the class without its package),
     *                the file name for the source rules
     * @param message what was found, for the text of the failure
     */
    public record Finding(String key, String message) {
    }

    /** A rule of this class, ready for a parameterized test: its id and the findings of the module it was made for. */
    public record Rule(String id, Supplier<List<Finding>> findings, Map<String, String> allowed) {

        @Override
        public String toString() {
            return id;
        }

        /** The problems of the module with this rule: empty when the rule holds. */
        public List<String> problems() {
            return PrincipleRules.problems(id, findings.get(), allowed);
        }
    }

    /**
     * The six rules for a module.
     *
     * @param classes  the production classes
     * @param mainJava the main sources
     * @param allowed  the allow list of each rule id (key to reason); a rule that is not in the map has none
     */
    public static List<Rule> all(JavaClasses classes, Path mainJava, Map<String, Map<String, String>> allowed) {
        return List.of(
                rule(BEAN_OF_OWN_TYPE, () -> beanMethodsOfOwnTypes(classes), allowed),
                rule(OPTIONAL_STORED, () -> optionalStoredOrPassed(classes), allowed),
                rule(OPTIONAL_GET, () -> optionalGet(classes), allowed),
                rule(OR_ELSE_NULL, () -> orElseNull(mainJava), allowed),
                rule(FILE_WRITE_IN_TRANSACTION, () -> fileWritesInTransactions(classes), allowed),
                rule(VARIABLE_EQUALS, () -> variableEquals(mainJava), allowed));
    }

    private static Rule rule(String id, Supplier<List<Finding>> findings, Map<String, Map<String, String>> allowed) {
        return new Rule(id, findings, allowed.getOrDefault(id, Map.of()));
    }

    /**
     * The problems of one rule: the findings that the allow list does not name, an allowed key whose reason is blank, and an
     * allowed key that no finding has.
     */
    public static List<String> problems(String ruleId, List<Finding> findings, Map<String, String> allowed) {
        List<String> problems = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Finding finding : findings) {
            if (allowed.containsKey(finding.key())) {
                used.add(finding.key());
            } else {
                problems.add(ruleId + " " + finding.key() + ": " + finding.message());
            }
        }
        new TreeMap<>(allowed).forEach((key, reason) -> {
            if (reason == null || reason.isBlank()) {
                problems.add(ruleId + " " + key + ": the allow list gives no reason");
            }
            if (!used.contains(key)) {
                problems.add(ruleId + " " + key + ": allowed, but nothing of the code needs it any more (remove the entry)");
            }
        });
        return problems;
    }

    // ---------------------------------------------------------------- bytecode rules

    /** ARC-PRN-01: a {@code @Bean} method whose return type is a type of {@code org.pgledger..}. */
    public static List<Finding> beanMethodsOfOwnTypes(JavaClasses classes) {
        List<Finding> found = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                String returned = method.getRawReturnType().getName();
                if (method.isAnnotatedWith(BEAN) && returned.startsWith(OWN_PACKAGE)) {
                    found.add(new Finding(key(method), "a @Bean method that returns " + returned
                            + ": a class of ours is a component, Spring finds it"));
                }
            }
        }
        return found;
    }

    /** ARC-PRN-02: a field, a constructor parameter or a method parameter of the raw type {@code Optional}. */
    public static List<Finding> optionalStoredOrPassed(JavaClasses classes) {
        List<Finding> found = new ArrayList<>();
        for (JavaClass type : classes) {
            type.getFields().stream().filter(field -> field.getRawType().isEquivalentTo(Optional.class))
                    .forEach(field -> found.add(new Finding(key(type, field.getName()),
                            "an Optional field (a record component is one): an Optional is the answer of a method")));
            Stream.concat(type.getMethods().stream(), type.getConstructors().stream())
                    .filter(unit -> !unit.getModifiers().contains(JavaModifier.SYNTHETIC))
                    .filter(unit -> unit.getRawParameterTypes().stream().anyMatch(p -> p.isEquivalentTo(Optional.class)))
                    .forEach(unit -> found.add(new Finding(key(unit), "an Optional parameter")));
        }
        return found;
    }

    /** ARC-PRN-03: a call of {@code Optional.get()}. */
    public static List<Finding> optionalGet(JavaClasses classes) {
        List<Finding> found = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                unit.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().isEquivalentTo(Optional.class)
                                && "get".equals(call.getTarget().getName()))
                        .forEach(call -> found.add(new Finding(key(unit),
                                "Optional.get() at " + call.getSourceCodeLocation())));
            }
        }
        return found;
    }

    /**
     * ARC-PRN-05: a write of {@code java.nio.file.Files} (or a file stream constructor) that a {@code @Transactional} method
     * reaches through calls of our own classes. The key is the transactional method.
     */
    public static List<Finding> fileWritesInTransactions(JavaClasses classes) {
        List<Finding> found = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                if (isTransactional(method)) {
                    Set<JavaCodeUnit> visited = new HashSet<>();
                    firstFileWrite(method, visited).ifPresent(write -> found.add(new Finding(key(method),
                            "a transaction that writes a file (" + write + "): the file stays changed when the commit fails")));
                }
            }
        }
        return found;
    }

    private static boolean isTransactional(JavaMethod method) {
        return TRANSACTIONAL.stream().anyMatch(name -> method.isAnnotatedWith(name) || method.getOwner().isAnnotatedWith(name));
    }

    /** The first write of a file that the code unit makes or reaches through calls of our own classes. */
    private static Optional<String> firstFileWrite(JavaCodeUnit unit, Set<JavaCodeUnit> visited) {
        if (!visited.add(unit)) {
            return Optional.empty();
        }
        for (JavaCall<?> call : unit.getCallsFromSelf()) {
            JavaClass owner = call.getTargetOwner();
            if (owner.isEquivalentTo(Files.class) && FILE_WRITES.contains(call.getTarget().getName())) {
                return Optional.of("Files." + call.getTarget().getName() + " at " + call.getSourceCodeLocation());
            }
            if (FILE_WRITING_CONSTRUCTORS.contains(owner.getName()) && "<init>".equals(call.getTarget().getName())) {
                return Optional.of("new " + owner.getSimpleName() + " at " + call.getSourceCodeLocation());
            }
            if (owner.getName().startsWith(OWN_PACKAGE)) {
                Optional<String> inner = call.getTarget().resolveMember().flatMap(target -> firstFileWrite(target, visited));
                if (inner.isPresent()) {
                    return inner;
                }
            }
        }
        return Optional.empty();
    }

    private static String key(JavaCodeUnit unit) {
        return key(unit.getOwner(), unit.getName());
    }

    private static String key(JavaClass owner, String member) {
        String name = owner.getName();
        return name.substring(owner.getPackageName().length() + 1) + "." + member;
    }

    // ---------------------------------------------------------------- source rules

    /** ARC-PRN-04: {@code .orElse(null)} in a main source. The key is the file name. */
    public static List<Finding> orElseNull(Path mainJava) {
        Map<String, Finding> found = new LinkedHashMap<>();
        for (Path file : javaFiles(mainJava)) {
            Matcher matcher = OR_ELSE_NULL_CALL.matcher(CodingScan.strip(file));
            if (matcher.find()) {
                found.putIfAbsent(file.getFileName().toString(), new Finding(file.getFileName().toString(),
                        file + ": orElse(null): map, orElseGet, orElseThrow, or stay an Optional"));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * ARC-PRN-06: {@code a.equals(b)} where neither side is a literal, a constant or a class literal and the receiver is no type
     * name. The key is the file name.
     */
    public static List<Finding> variableEquals(Path mainJava) {
        Map<String, Finding> found = new LinkedHashMap<>();
        for (Path file : javaFiles(mainJava)) {
            String text = CodingScan.strip(file);
            Matcher matcher = EQUALS_CALL.matcher(text);
            while (matcher.find()) {
                String receiver = receiverBefore(text, matcher.start());
                String argument = argumentAfter(text, matcher.end());
                if (!fixed(receiver) && !fixed(argument)) {
                    found.putIfAbsent(file.getFileName().toString(), new Finding(file.getFileName().toString(),
                            file + ": " + receiver + ".equals(" + argument + "): Objects.equals for two variables, == for an enum, "
                                    + "a literal or a constant first"));
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** A literal, null, a number, a constant, a class literal or a type name: something that is not a variable. */
    private static boolean fixed(String expression) {
        String text = expression.strip();
        if (text.isEmpty() || text.startsWith("\"") || text.startsWith("'") || "null".equals(text)
                || Character.isDigit(text.charAt(0)) || text.endsWith(".class")) {
            return true;
        }
        if (text.endsWith(")") || text.endsWith("]")) {
            return false;
        }
        String last = text.substring(text.lastIndexOf('.') + 1).strip();
        if (CONSTANT_NAME.matcher(last).matches()) {
            return true;
        }
        // a type name, qualified or not (Objects, java.util.Arrays): a variable or a field is written in lower case
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0));
    }

    /** The receiver expression that ends just before the dot at {@code dot}. */
    private static String receiverBefore(String text, int dot) {
        int end = dot;
        int i = skipBlanksBack(text, dot - 1);
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ')' || c == ']') {
                i = skipBlanksBack(text, matching(text, i) - 1);
                // a call or an index follows a name; a parenthesised expression has no name before it
                if (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i = identifierStart(text, i);
                }
            } else if (c == '"') {
                i = i - 2;
            } else if (Character.isJavaIdentifierPart(c)) {
                i = identifierStart(text, i);
            } else {
                break;
            }
            int before = skipBlanksBack(text, i);
            if (before >= 0 && text.charAt(before) == '.') {
                i = skipBlanksBack(text, before - 1);
            } else {
                break;
            }
        }
        return text.substring(i + 1, end).strip();
    }

    /** The first argument after the parenthesis that ends at {@code open} (the index after the parenthesis). */
    private static String argumentAfter(String text, int open) {
        int depth = 1;
        int i = open;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return text.substring(open, Math.max(open, i - 1)).strip();
    }

    private static int skipBlanksBack(String text, int from) {
        int i = from;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int identifierStart(String text, int from) {
        int i = from;
        while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) {
            i--;
        }
        return i;
    }

    /** The index of the bracket that opens the one at {@code close}. */
    private static int matching(String text, int close) {
        char closing = text.charAt(close);
        char opening = closing == ')' ? '(' : '[';
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == closing) {
                depth++;
            } else if (c == opening) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
