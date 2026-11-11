package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Hands every test method that asks for a {@link HookFixture} (the hook in process) or a {@link HookJar} (the built
 * jar as a process) its own fresh one. The working files of a {@link HookJar} are deleted when the test ends.
 */
final class HookExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(HookExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == HookFixture.class || type == HookJar.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        if (parameter.getParameter().getType() == HookFixture.class) {
            return new HookFixture();
        }
        try {
            Path work = Files.createTempDirectory("ledger-hook-test");
            context.getStore(NAMESPACE).put(work, (AutoCloseable) () -> delete(work));
            return new HookJar(work);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        }
    }
}
