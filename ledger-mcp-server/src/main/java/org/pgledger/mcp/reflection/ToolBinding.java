package org.pgledger.mcp.reflection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.pgledger.attestation.ArgumentsDigest;
import org.pgledger.domain.context.ToolScope;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.mcp.tool.PresenceAware;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.PropertyBindingException;
import tools.jackson.databind.json.JsonMapper;

/**
 * One tool as a method of an adapter bean: it binds the arguments of a call to the parameters of the method and
 * calls it. This is the one place that reads arguments of a call and the one place that calls the method by
 * reflection; everything wrong with the arguments (an unknown key, a missing one, a wrong type, a value outside a
 * closed set) is raised here as one VALIDATION_FAILED with all its issues.
 *
 * <p>An argument that is an object or an array and arrives as text is the old form of the tool contract (a JSON
 * document in a string); it is read as that document here and nowhere else.
 *
 * <p><b>Why this is not Spring AI's own callback</b> (principle 2.1: the answer to "does Spring offer it?" is
 * recorded here). Spring AI's {@code SyncMcpToolMethodCallback} binds and calls an {@code @McpTool} method, and it is
 * used to make the tool definitions, but it cannot give the contract of this ledger:
 * <ul>
 * <li>A binding or business failure becomes a free-text error result ({@code createSyncErrorResult}: the exception
 * message and the message of its cause as one text, no structured content); the error token with one issue per
 * argument (path, reason, allowed values) that the ledger contract needs cannot be produced there. Evidence:
 * {@code javap} of {@code SyncMcpToolMethodCallback.apply} and {@code AbstractSyncMcpToolMethodCallback} in
 * spring-ai-mcp-annotations 2.0.2 (the class is {@code final}, {@code buildTypedArgument} is {@code protected}, the
 * constructor with a {@code Class<? extends Throwable>} only stores the class for the asynchronous callback).</li>
 * <li>Its argument binding reads with a lenient mapper: an unknown key is accepted (spike report c8, section c of
 * {@code SPIKE_REPORT.md}); strict reading and the text form of an object are this class.</li>
 * <li>The SDK's own input validation ({@code McpServer.SyncSpecification#validateToolInputs}, 2.1.0) answers a
 * schema violation as one free-text error result; it is switched off in {@code McpServerConfiguration} for that
 * reason, and no Spring AI property exists for it.</li>
 * </ul>
 * Documents (read): Spring AI reference "MCP Annotations - Server"
 * (https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html). Its section "Exception Handling"
 * says "A {@code RuntimeException} is converted to an error {@code CallToolResult} and conveyed to the model, which
 * can reason about it or retry." and names no setting that changes the exception class, so a structured error token
 * cannot be produced by that path. The page does not describe argument binding or conversion, input validation, or a
 * customization of the input schema ({@code additionalProperties}, enumerations); the attributes of
 * {@code @McpToolParam} are only {@code description} and {@code required}. For all of these the documentation has no
 * feature. The MCP Java SDK javadoc and the Bean Validation documentation were not opened; the statements about the
 * SDK rest on {@code javap} of the jars (spring-ai-mcp-annotations 2.0.2, mcp-core 2.1.0) and on the spike report.
 *
 * <p><b>Why the required-argument checks are not Jakarta constraints</b> (principle 2.2 category 9). The services
 * validate every field rule with Jakarta Validation. What is checked here happens before any object exists that a
 * constraint could sit on: a key that the tool does not declare, a declared key that is missing, a value of the wrong
 * type, and a value outside a closed set are facts about the call, and a Jakarta violation would carry the path of a
 * method parameter, not of the wire key. The checks are derived from the declarations ({@code @McpToolParam}
 * required, {@code @Choice}), not written per argument.
 */
public final class ToolBinding {

    private final String name;
    private final Object bean;
    private final Method method;
    private final List<ToolParameter> parameters;
    private final List<CodeSlot> codeSlots;
    private final List<ChoiceSlot> choiceSlots;
    private final JsonMapper mapper;

    ToolBinding(String name, Object bean, Method method, List<ToolParameter> parameters, List<CodeSlot> codeSlots,
            List<ChoiceSlot> choiceSlots, JsonMapper mapper) {
        this.name = name;
        this.bean = bean;
        this.method = method;
        this.parameters = List.copyOf(parameters);
        this.codeSlots = List.copyOf(codeSlots);
        this.choiceSlots = List.copyOf(choiceSlots);
        this.mapper = mapper;
    }

    public String name() {
        return name;
    }

    /** The places of the input schema that hold a common code. */
    public List<CodeSlot> codeSlots() {
        return codeSlots;
    }

    /** The places of the input schema that hold a value of a closed set. */
    public List<ChoiceSlot> choiceSlots() {
        return choiceSlots;
    }

    /**
     * Binds the arguments and calls the tool. The name of the tool and the digest of the arguments are bound to
     * {@link ToolScope} for the call, which is how the write gate learns which tool and which arguments the author
     * attestation of the call has to be signed for.
     *
     * @throws ValidationException when the arguments do not fit the tool
     */
    public Object call(Map<String, Object> arguments) {
        Map<String, Object> given = arguments == null ? Map.of() : arguments;
        List<ValidationIssue> issues = new ArrayList<>();
        List<JacksonException> causes = new ArrayList<>();
        Set<String> declared = Set.copyOf(parameters.stream().map(ToolParameter::name).toList());
        given.keySet().stream().filter(key -> !declared.contains(key)).sorted().forEach(key -> issues.add(
                new ValidationIssue(key, ValidationReason.UNKNOWN_ARGUMENT, List.of(), List.of())));
        Object[] values = new Object[parameters.size()];
        for (int i = 0; i < values.length; i++) {
            ToolParameter parameter = parameters.get(i);
            Object raw = given.get(parameter.name());
            if (raw == null) {
                if (parameter.required()) {
                    issues.add(new ValidationIssue(parameter.name(), ValidationReason.REQUIRED, List.of(),
                            List.of()));
                }
            } else {
                int issuesBefore = issues.size();
                values[i] = bind(parameter, raw, issues, causes);
                // a text that holds the JSON null is no value either (the old form of "the argument is missing")
                if (values[i] == null && parameter.required() && issues.size() == issuesBefore) {
                    issues.add(new ValidationIssue(parameter.name(), ValidationReason.REQUIRED, List.of(),
                            List.of()));
                }
            }
        }
        for (ChoiceSlot slot : choiceSlots) {
            checkChoice(slot, values, issues);
        }
        if (!issues.isEmpty()) {
            throw new ValidationException(ValidationCode.VALIDATION_FAILED, List.of(issues.getFirst().path()),
                    issues, Map.of(), causes.isEmpty() ? null : causes.getFirst());
        }
        // the gate compares the tool and the arguments the author attestation was signed for with those of this call
        return ScopedValue.where(ToolScope.CURRENT, name).where(ToolScope.ARGUMENTS_DIGEST, argumentsDigest(given))
                .call(() -> invoke(values));
    }

    /**
     * The digest of the arguments exactly as they arrived ({@link ArgumentsDigest}: RFC 8785 canonical form, SHA-256,
     * the same class the client hook signs with), so the attestation covers what was sent and nothing else: a captured
     * token cannot carry other content (F1/F3). A call without {@code _hostAttestation} has nothing signed (the gate
     * refuses it before it looks at a digest), and arguments that are no JSON (which the wire cannot carry) have none
     * either: {@code null} equals no signed digest.
     */
    private static String argumentsDigest(Map<String, Object> given) {
        if (!given.containsKey(ArgumentsDigest.ATTESTATION_MEMBER)) {
            return null;
        }
        try {
            return ArgumentsDigest.of(given);
        } catch (IllegalArgumentException notJson) {
            return null;
        }
    }

    private Object bind(ToolParameter parameter, Object raw, List<ValidationIssue> issues,
            List<JacksonException> causes) {
        Object source = raw;
        try {
            if (source instanceof String text && parameter.structured()) {
                source = mapper.readValue(text, Object.class);
            }
            if (source == null) {
                return null;
            }
            JavaType type = mapper.constructType(parameter.type());
            Object value = mapper.convertValue(source, type);
            if (value instanceof PresenceAware<?> aware && source instanceof Map<?, ?> written) {
                return aware.withMentioned(Set.copyOf(written.keySet().stream().map(String::valueOf).toList()));
            }
            return value;
        } catch (JacksonException failure) {
            issues.add(issue(parameter.name(), failure));
            causes.add(failure);
            return null;
        }
    }

    private static ValidationIssue issue(String root, JacksonException failure) {
        StringBuilder path = new StringBuilder(root);
        for (JacksonException.Reference step : failure.getPath()) {
            if (step.getIndex() >= 0) {
                path.append('[').append(step.getIndex()).append(']');
            } else if (step.getPropertyName() != null) {
                path.append('.').append(step.getPropertyName());
            }
        }
        return switch (failure) {
            case PropertyBindingException unknown ->
                new ValidationIssue(path.toString(), ValidationReason.UNKNOWN_ARGUMENT, List.of(), List.of());
            case MismatchedInputException mismatch when mismatch.getTargetType() != null ->
                new ValidationIssue(path.toString(), ValidationReason.WRONG_TYPE,
                        List.of(mismatch.getTargetType().getSimpleName()), List.of());
            case InvalidTypeIdException typeId -> typeIdIssue(path.toString(), typeId);
            // the text of the JSON library names internal classes: the cause keeps it for the log, the caller
            // gets the sentence of the reason bundle
            default -> new ValidationIssue(path.toString(), ValidationReason.INVALID_JSON, List.of(), List.of());
        };
    }

    /**
     * The {@code type} key of a variant is missing (REQUIRED) or names no variant (UNKNOWN_VALUE with the names
     * that exist, read from the {@code @JsonSubTypes} of the base type); the class names of Jackson's text are not
     * passed on.
     */
    private static ValidationIssue typeIdIssue(String path, InvalidTypeIdException failure) {
        Class<?> base = failure.getBaseType() == null ? null : failure.getBaseType().getRawClass();
        JsonTypeInfo info = base == null ? null : base.getAnnotation(JsonTypeInfo.class);
        JsonSubTypes subtypes = base == null ? null : base.getAnnotation(JsonSubTypes.class);
        if (info == null || subtypes == null) {
            return new ValidationIssue(path, ValidationReason.INVALID_JSON, List.of(), List.of());
        }
        String at = path + "." + info.property();
        if (failure.getTypeId() == null) {
            return new ValidationIssue(at, ValidationReason.REQUIRED, List.of(), List.of());
        }
        return new ValidationIssue(at, ValidationReason.UNKNOWN_VALUE, List.of(failure.getTypeId()),
                Arrays.stream(subtypes.value()).map(JsonSubTypes.Type::name).toList());
    }

    private void checkChoice(ChoiceSlot slot, Object[] values, List<ValidationIssue> issues) {
        for (int i = 0; i < values.length; i++) {
            if (Objects.equals(parameters.get(i).name(), slot.parameter())) {
                for (String candidate : texts(values[i])) {
                    if (!slot.values().contains(candidate)) {
                        issues.add(new ValidationIssue(parameters.get(i).name(), ValidationReason.UNKNOWN_VALUE,
                                List.of(candidate), slot.values()));
                    }
                }
            }
        }
    }

    private static List<String> texts(Object value) {
        return switch (value) {
            case null -> List.of();
            case String text -> List.of(text);
            case List<?> list -> list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
            default -> List.of();
        };
    }

    private Object invoke(Object[] values) {
        try {
            return method.invoke(bean, values);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("tool " + name + " raised a checked exception", cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("tool " + name + " is not callable", failure);
        }
    }
}
