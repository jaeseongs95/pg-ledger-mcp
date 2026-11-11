package org.pgledger.mcp.reflection;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pgledger.domain.rule.LifecycleState;
import org.pgledger.domain.value.Token;
import org.pgledger.mcp.tool.Choice;
import org.pgledger.mcp.tool.CommonCode;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the tools of an adapter bean (its {@code @McpTool} methods) and turns each into a {@link ToolBinding}.
 * Reading annotations of methods and record components is reflection, which stays in this package.
 */
@Component
public final class ToolBindings {

    private final JsonMapper mapper;

    /**
     * Derives the mapper that binds arguments from the {@code JsonMapper} bean of Spring Boot, stricter than the
     * default: an unknown key, a text where a number is expected and a fraction where a whole number is expected
     * are refused instead of being coerced.
     */
    public ToolBindings(JsonMapper boot) {
        this.mapper = boot.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }

    /** The tools of the adapter, one binding per {@code @McpTool} method. */
    public List<ToolBinding> bind(Object adapter) {
        Class<?> target = AopUtils.getTargetClass(adapter);
        Map<Method, McpTool> methods = MethodIntrospector.selectMethods(target,
                (MethodIntrospector.MetadataLookup<McpTool>) method -> AnnotatedElementUtils
                        .findMergedAnnotation(method, McpTool.class));
        List<ToolBinding> bindings = new ArrayList<>();
        methods.forEach((method, tool) -> bindings.add(binding(adapter, AopUtils.selectInvocableMethod(method,
                adapter.getClass()), tool.name())));
        return bindings;
    }

    private ToolBinding binding(Object adapter, Method method, String name) {
        List<ToolParameter> parameters = new ArrayList<>();
        List<CodeSlot> codes = new ArrayList<>();
        List<ChoiceSlot> choices = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            McpToolParam declared = parameter.getAnnotation(McpToolParam.class);
            Class<?> raw = ResolvableType.forType(parameter.getParameterizedType()).resolve(Object.class);
            boolean scalar = raw == String.class || raw == Boolean.class || Number.class.isAssignableFrom(raw);
            parameters.add(new ToolParameter(parameter.getName(), declared != null && declared.required(),
                    parameter.getParameterizedType(), !scalar));
            collect(parameter.getName(), parameter.getName(), parameter, parameter.getParameterizedType(), codes,
                    choices);
        }
        return new ToolBinding(name, adapter, method, parameters, codes, choices, mapper);
    }

    private void collect(String parameter, String path, AnnotatedElement element,
            java.lang.reflect.Type type, List<CodeSlot> codes, List<ChoiceSlot> choices) {
        ResolvableType resolvable = ResolvableType.forType(type);
        Class<?> raw = resolvable.resolve(Object.class);
        boolean list = List.class.isAssignableFrom(raw);
        String here = list ? path + "[]" : path;
        CommonCode code = element.getAnnotation(CommonCode.class);
        if (code != null) {
            codes.add(new CodeSlot(here, code.group(), Arrays.asList(code.fixed())));
        }
        Choice choice = element.getAnnotation(Choice.class);
        if (choice != null) {
            choices.add(new ChoiceSlot(parameter, here, values(choice)));
        }
        Class<?> inner = list ? resolvable.getGeneric(0).resolve(Object.class) : raw;
        if (inner.isRecord()) {
            for (RecordComponent component : inner.getRecordComponents()) {
                JsonProperty wire = component.getAnnotation(JsonProperty.class);
                String key = wire == null ? component.getName() : wire.value();
                collect(parameter, here + "." + key, component, component.getGenericType(), codes, choices);
            }
        }
    }

    /** The allowed values of a closed set: the wire token of a constant that has one, otherwise its name. */
    private static List<String> values(Choice choice) {
        Set<String> values = new LinkedHashSet<>();
        Set<String> only = Set.of(choice.only());
        for (Class<? extends Enum<?>> type : choice.from()) {
            for (Enum<?> constant : type.getEnumConstants()) {
                boolean wanted = only.isEmpty() || only.contains(constant.name());
                boolean verification = constant instanceof LifecycleState state && state.verification();
                if (wanted && !(verification && choice.verification() == Choice.Verification.EXCLUDED)) {
                    values.add(constant instanceof Token token ? token.token() : constant.name());
                }
            }
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("a @Choice that allows nothing: " + Arrays.toString(choice.from()));
        }
        return List.copyOf(values);
    }
}
