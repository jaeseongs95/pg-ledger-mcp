package org.pgledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.mcp.support.McpServerTest;
import org.pgledger.mcp.support.McpSession;
import org.pgledger.mcp.support.SchemaView;
import org.pgledger.mcp.support.ToolNames;
import org.pgledger.mcp.tool.ToolAdapter;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The texts of the tools: a description that reaches the model is a text, never a message key, and every key that the
 * adapters name exists in the bundle.
 */
@McpServerTest
class CatalogTextsTest {

    private static final List<String> KEY_PREFIXES = List.of("tool.", "param.", "field.");

    private static List<String> descriptionsOf(SchemaView schema) {
        return schema.nodes().map(SchemaView::description).filter(text -> text != null).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.pgledger.mcp.support.ToolNames#all")
    @DisplayName("1.5/MCW-017 texts: no description of a tool, of its input or of its output is still a message key")
    void noDescriptionIsAMessageKey(String name, McpSession session) {
        Tool tool = session.tool(name);
        List<String> texts = Stream.of(List.of(tool.description()),
                descriptionsOf(SchemaView.of(session.mapper(), tool.inputSchema())),
                descriptionsOf(SchemaView.of(session.mapper(), tool.outputSchema()))).flatMap(List::stream)
                .toList();

        assertThat(texts).isNotEmpty().allSatisfy(text -> assertThat(text).isNotBlank()
                .satisfies(value -> KEY_PREFIXES.forEach(prefix -> assertThat(value).doesNotStartWith(prefix))));
    }

    @Test
    @DisplayName("1.5/MCW-017 texts: the tools of the server are 22 and each has a description")
    void everyToolHasADescription(McpSession session) {
        assertThat(session.tools()).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(ToolNames.all());
        assertThat(session.tools()).allSatisfy(tool -> assertThat(tool.description()).isNotBlank());
    }

    private static Stream<String> keysOf(Object adapter) {
        Class<?> type = AopUtils.getTargetClass(adapter);
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(McpTool.class))
                .flatMap(CatalogTextsTest::keysOfTool);
    }

    private static Stream<String> keysOfTool(Method method) {
        Stream<String> own = Stream.of(method.getAnnotation(McpTool.class).description());
        Stream<String> parameters = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(McpToolParam.class))
                .flatMap(CatalogTextsTest::keysOfParameter);
        return Stream.concat(own, parameters);
    }

    private static Stream<String> keysOfParameter(Parameter parameter) {
        Stream<String> own = Stream.of(parameter.getAnnotation(McpToolParam.class).description());
        Stream<String> components = parameter.getType().isRecord()
                ? Arrays.stream(parameter.getType().getRecordComponents()).map(CatalogTextsTest::keyOfComponent)
                        .flatMap(Stream::ofNullable)
                : Stream.empty();
        return Stream.concat(own, components);
    }

    private static String keyOfComponent(RecordComponent component) {
        JsonPropertyDescription description = component.getAccessor().getAnnotation(JsonPropertyDescription.class);
        return description == null ? null : description.value();
    }

    @Test
    @DisplayName("1.5/MCW-017 texts: every message key that an adapter names exists in messages/mcp.properties")
    void everyKeyOfTheAdaptersExists(@Autowired List<ToolAdapter> adapters) {
        ResourceBundle texts = ResourceBundle.getBundle("messages/mcp", Locale.ROOT);
        List<String> used = adapters.stream().flatMap(CatalogTextsTest::keysOf).distinct().toList();

        assertThat(used.stream().filter(key -> key.startsWith("tool.")).count()).isEqualTo(ToolNames.all().size());
        assertThat(used.stream().filter(key -> key.startsWith("param.")).count()).isPositive();
        assertThat(used.stream().filter(key -> key.startsWith("field.")).count()).isPositive();
        assertThat(used.stream().filter(key -> !texts.containsKey(key)).toList()).isEmpty();
    }
}
