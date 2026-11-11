package org.pgledger.hook;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Hands every test method that asks for a {@link HookFixture} (the hook in process), a {@link HookJar} (the built jar
 * as a process) or a {@link TestWorkspace} a fresh one. Fixture and jar of one test share one {@link TestWorkspace}, so
 * they use the same key folder; the workspace (working files and key folder) is deleted when the test ends.
 */
final class HookExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(HookExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == HookFixture.class || type == HookJar.class || type == TestWorkspace.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        TestWorkspace workspace = context.getStore(NAMESPACE)
                .getOrComputeIfAbsent(TestWorkspace.class, type -> new TestWorkspace(), TestWorkspace.class);
        Class<?> type = parameter.getParameter().getType();
        if (type == HookFixture.class) {
            return new HookFixture(workspace.keys());
        }
        if (type == HookJar.class) {
            return new HookJar(workspace.work(), workspace.keys());
        }
        return workspace;
    }
}
