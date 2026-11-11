package org.pgledger.mcp.catalog;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.mcp.error.ToolResults;
import org.pgledger.mcp.reflection.CodeSlot;
import org.pgledger.mcp.reflection.ToolBinding;
import org.pgledger.mcp.reflection.ToolBindings;
import org.pgledger.mcp.tool.ToolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.stereotype.Component;

/**
 * The tools of the server as the model sees them. Once the context is up, the catalog takes the tool definitions
 * that Spring AI made from the {@code @McpTool} methods and registers each one again, built for this server: every
 * object of its input schema closed (an unknown argument is refused), the descriptions resolved from the message
 * bundle, the closed sets of the domain as enumerations, and the places that hold a common code filled with the
 * active codes of the group. The handler of a registered tool binds the arguments and calls the adapter method
 * through {@link ToolBinding}, so the errors become the tool result in one place.
 *
 * <p>The codes live in the database and other processes change them. {@link #refreshIfChanged()} reads the
 * {@code code_revision}; when it moved, only the tools whose definition really changed are registered again, which
 * announces {@code list_changed} to the open sessions. A tool is replaced in one step, never removed first, so a
 * client that lists the tools meanwhile gets the old or the new definition and never a partial list.
 *
 * <p>{@code toolSpecs} and the server are injected lazily: a bean that asks for them while the context is still
 * being built would see the server without any annotated tool.
 */
@Component
public class ToolCatalog implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCatalog.class);

    private final McpSyncServer server;
    private final List<SyncToolSpecification> annotatedSpecs;
    private final List<ToolAdapter> adapters;
    private final ToolBindings bindings;
    private final CommonCodeService codes;
    private final CodeRevisionRepository revision;
    private final ToolResults results;
    private final MessageSourceAccessor messages;

    /** The tool definitions as Spring AI made them, and how to call each tool, by tool name. */
    private final Map<String, Tool> base = new LinkedHashMap<>();
    private final Map<String, ToolBinding> bound = new LinkedHashMap<>();
    private long registeredRevision = -1;

    public ToolCatalog(@Lazy McpSyncServer server,
            @Lazy @Qualifier("toolSpecs") List<SyncToolSpecification> annotatedSpecs, List<ToolAdapter> adapters,
            ToolBindings bindings, CommonCodeService codes, CodeRevisionRepository revision, ToolResults results,
            MessageSourceAccessor modelMessages) {
        this.server = server;
        this.annotatedSpecs = annotatedSpecs;
        this.adapters = adapters;
        this.bindings = bindings;
        this.codes = codes;
        this.revision = revision;
        this.results = results;
        this.messages = modelMessages;
    }

    @Override
    public void afterSingletonsInstantiated() {
        synchronized (this) {
            annotatedSpecs.forEach(spec -> base.put(spec.tool().name(), spec.tool()));
            adapters.forEach(adapter -> bindings.bind(adapter).forEach(binding -> bound.put(binding.name(),
                    binding)));
            registerAll();
        }
    }

    /** Adds the tools of a new adapter bean to the running server (the server announces {@code list_changed}). */
    public synchronized void register(ToolAdapter adapter) {
        SyncMcpAnnotationProviders.toolSpecifications(List.of(adapter))
                .forEach(spec -> base.put(spec.tool().name(), spec.tool()));
        bindings.bind(adapter).forEach(binding -> bound.put(binding.name(), binding));
        registerAll();
    }

    /** Registers the tools again when the common codes changed since the last registration. */
    public synchronized void refreshIfChanged() {
        long current = revision.current();
        if (current != registeredRevision) {
            LOG.info("the common codes changed (revision {}), the tools are registered again", current);
            registerAll();
        }
    }

    private void registerAll() {
        long readRevision = revision.current();
        Map<String, Tool> registered = new LinkedHashMap<>();
        server.listTools().forEach(tool -> registered.put(tool.name(), tool));
        bound.forEach((name, binding) -> {
            Tool wanted = definition(base.get(name), binding);
            if (!wanted.equals(registered.get(name))) {
                server.addTool(SyncToolSpecification.builder().tool(wanted)
                        .callHandler((exchange, request) -> results.run(() -> binding.call(request.arguments())))
                        .build());
            }
        });
        registeredRevision = readRevision;
    }

    private Tool definition(Tool original, ToolBinding binding) {
        SchemaDocument input = SchemaDocument.copyOf(original.inputSchema());
        input.closeObjects();
        binding.choiceSlots().forEach(slot -> input.restrict(slot.path(), slot.values()));
        input.rewriteDescriptions(this::text);
        binding.codeSlots().forEach(slot -> fillCodes(input, slot));
        Tool.Builder definition = Tool.builder(original.name(), input.toSdk()).title(original.title())
                .description(text(original.description())).annotations(original.annotations())
                .meta(original.meta());
        if (original.outputSchema() != null) {
            SchemaDocument output = SchemaDocument.copyOf(original.outputSchema());
            output.rewriteDescriptions(this::text);
            output.allowNullProperties();
            definition.outputSchema(output.toSdk());
        }
        return definition.build();
    }

    /** The enumeration and the description of a code place: the fixed values and the active codes of the group. */
    private void fillCodes(SchemaDocument input, CodeSlot slot) {
        List<CommonCode> active = codes.list(slot.group()).stream().filter(CommonCode::active)
                .sorted(Comparator.comparingInt(CommonCode::sortOrder).thenComparing(code -> code.code().value()))
                .toList();
        input.restrict(slot.path(), Stream.concat(slot.fixed().stream(), active.stream()
                .map(code -> code.code().value())).distinct().toList());
        String listing = active.isEmpty() ? empty(slot.group())
                : String.join("\n", active.stream().map(ToolCatalog::line).toList());
        String described = input.description(slot.path()).orElse("");
        input.describe(slot.path(), described.isBlank() ? listing : described + "\n" + listing);
    }

    /** {@code CODE name_ko (name_en): description_ko / description_en}, the texts exactly as stored. */
    private static String line(CommonCode code) {
        String description = code.descriptionKo() == null || code.descriptionKo().isBlank() ? ""
                : ": " + code.descriptionKo() + " / " + code.descriptionEn();
        return code.code().value() + " " + code.nameKo() + " (" + code.nameEn() + ")" + description;
    }

    private String empty(CodeGroup group) {
        return messages.getMessage("catalog.empty", new Object[] {group.name()}, group.name());
    }

    /** A description in a tool declaration is a message key; a text that is no key stays as it is. */
    private String text(String keyOrText) {
        return messages.getMessage(keyOrText, keyOrText);
    }
}
