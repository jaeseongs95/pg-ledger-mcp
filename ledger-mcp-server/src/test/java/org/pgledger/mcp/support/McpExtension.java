package org.pgledger.mcp.support;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a test method of an {@link McpServerTest} class what it works with: a {@link McpSession} (a real client of
 * the running server, closed after the test), a {@link Scenario} (a project of its own) or the application context.
 */
public final class McpExtension implements ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
            .create(McpExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        Class<?> type = parameter.getParameter().getType();
        return type == McpSession.class || type == Scenario.class || type == ApplicationContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        ApplicationContext context = SpringExtension.getApplicationContext(extension);
        Class<?> type = parameter.getParameter().getType();
        if (type == McpSession.class) {
            return extension.getStore(NAMESPACE).computeIfAbsent(new SessionKey(parameter.getIndex(),
                    extension.getUniqueId()), key -> new ClosingSession(open(context)), ClosingSession.class)
                    .session();
        }
        if (type == Scenario.class) {
            return new Scenario(context);
        }
        if (type == ApplicationContext.class) {
            return context;
        }
        throw new ParameterResolutionException("unsupported parameter " + type);
    }

    private static McpSession open(ApplicationContext context) {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return McpSession.open(port, McpTestSettings.TOKEN, context.getBean(JsonMapper.class));
    }

    /** The key of a session in the store of one test. */
    private record SessionKey(int index, String test) {
    }

    /** A session that the store closes when the test is over. */
    private record ClosingSession(McpSession session) implements AutoCloseable {

        @Override
        public void close() {
            session.close();
        }
    }
}
