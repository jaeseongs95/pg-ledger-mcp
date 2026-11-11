package org.pgledger.cli.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.pgledger.cli.CliException;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.LedgerCliApplication;
import org.pgledger.cli.secret.SecretInput;
import org.springframework.boot.ExitCodeEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * JUnit extension that hands a test a {@link Cli} to start the real application the way {@code main} does. Only the input and
 * output boundaries can be swapped. The exit code is the one Boot publishes as an {@link ExitCodeEvent} after
 * {@code CliExitCodeMapper} mapped the failure, or the code of {@link SpringApplication#exit} when the run succeeded; the failure
 * report itself is what Boot logs ({@code CliFailureAnalyzer}), which a test reads with {@code OutputCaptureExtension}.
 */
public final class CliExtension implements ParameterResolver {

    /** @param failure what {@code SpringApplication.run} threw, or null; @param context closed by the time the run returns */
    public record Outcome(int exitCode, RuntimeException failure, ConfigurableApplicationContext context) {

        /** The error code of the refusal in the failure chain, if the run was refused. */
        public Optional<ErrorCode> code() {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof CliException refusal) {
                    return Optional.of(refusal.code());
                }
            }
            return Optional.empty();
        }
    }

    public static final class Cli {

        /** @param input null keeps the product Console input; @param output null keeps the product stream output */
        public Outcome run(Path secretsDirectory, SecretInput input, CliOutput output, String... args) {
            List<String> all = new ArrayList<>(List.of(args));
            all.add("--ledger.secrets.directory=" + secretsDirectory);
            AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
            ExitCodes exitCodes = new ExitCodes();
            SpringApplicationBuilder builder = new SpringApplicationBuilder(LedgerCliApplication.class)
                    .listeners(exitCodes)
                    .initializers(ctx -> {
                        started.set(ctx);
                        GenericApplicationContext generic = (GenericApplicationContext) ctx;
                        if (input != null) {
                            generic.registerBean("testSecretInput", SecretInput.class, () -> input, bd -> bd.setPrimary(true));
                        }
                        if (output != null) {
                            generic.registerBean("testCliOutput", CliOutput.class, () -> output, bd -> bd.setPrimary(true));
                        }
                    });
            try {
                ConfigurableApplicationContext context = builder.run(all.toArray(String[]::new));
                return new Outcome(SpringApplication.exit(context), null, context);
            } catch (RuntimeException failure) {
                // The test entry plays the CLI boundary (what main() does): whatever the start throws is handed to the
                // test in the Outcome, which asserts on it. Nothing is swallowed.
                return new Outcome(exitCodes.code.get(), failure, started.get());
            }
        }
    }

    private static final class ExitCodes implements ApplicationListener<ExitCodeEvent> {

        private final AtomicInteger code = new AtomicInteger();

        @Override
        public void onApplicationEvent(ExitCodeEvent event) {
            code.set(event.getExitCode());
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        return parameter.getParameter().getType() == Cli.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        return new Cli();
    }
}
