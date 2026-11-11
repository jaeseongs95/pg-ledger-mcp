package org.pgledger.cli.support;

import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.pgledger.cli.CliException;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.LedgerCliApplication;
import org.pgledger.cli.key.SessionKeyRegistry;
import org.pgledger.cli.secret.ProcessUser;
import org.pgledger.cli.secret.SecretInput;
import org.pgledger.database.repository.LegacySourceRepository;
import org.springframework.boot.ExitCodeEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * JUnit extension that hands a test a {@link Cli} to start the real application the way {@code main} does. Only the input and
 * output boundaries can be swapped. The exit code is the one Boot publishes as an {@link ExitCodeEvent} after
 * {@code CliExitCodeMapper} mapped the failure, or the code of {@link SpringApplication#exit} when the run succeeded; the failure
 * report itself is what Boot logs ({@code CliFailureAnalyzer}), which a test reads with {@code OutputCaptureExtension}.
 */
public final class CliExtension implements ParameterResolver {

    /** @param failure what {@code SpringApplication.run} threw, or null; @param context closed by the time the run returns */
    public record Outcome(int exitCode, RuntimeException failure, ConfigurableApplicationContext context) {

        /** The refusal in the failure chain, if the run was refused. */
        public Optional<CliException> refusal() {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof CliException refusal) {
                    return Optional.of(refusal);
                }
            }
            return Optional.empty();
        }

        /** The error code of the refusal in the failure chain, if the run was refused. */
        public Optional<ErrorCode> code() {
            return refusal().map(CliException::code);
        }
    }

    public static final class Cli {

        private final DataSource database;
        private final String processUser;
        private final List<String> extraArguments;
        private final LegacySourceRepository legacySource;

        Cli() {
            this(null, null, List.of(), null);
        }

        private Cli(DataSource database, String processUser, List<String> extraArguments, LegacySourceRepository legacySource) {
            this.database = database;
            this.processUser = processUser;
            this.extraArguments = extraArguments;
            this.legacySource = legacySource;
        }

        /**
         * A Cli whose application runs on the {@code embedded} profile (the way the admin web tests do): the ledger beans
         * (the real services, repositories and transaction manager) are created over this database, which the test has
         * built with the real migrator, instead of over the DataSource of a registered driver. The DataSource is handed
         * in without a close method, so it is still open when the run has closed the context and the test reads the rows.
         */
        public Cli withDatabase(DataSource dataSource) {
            return new Cli(dataSource, processUser, extraArguments, legacySource);
        }

        /** A Cli that runs as this process user (the account name that the audit trail records as {@code cli:<name>}). */
        public Cli asProcessUser(String name) {
            return new Cli(database, name, extraArguments, legacySource);
        }

        /** A Cli whose application has this legacy ledger as the source of {@code import-legacy} (the way the settings {@code ledger.import.source.*} would configure it). */
        public Cli withLegacySource(LegacySourceRepository source) {
            return new Cli(database, processUser, extraArguments, source);
        }

        /** A Cli that also gets these command line arguments (for example {@code --spring.config.import=...}). */
        public Cli withArguments(String... arguments) {
            List<String> all = new ArrayList<>(extraArguments);
            all.addAll(List.of(arguments));
            return new Cli(database, processUser, List.copyOf(all), legacySource);
        }

        /** @param input null keeps the product Console input; @param output null keeps the product stream output */
        public Outcome run(Path secretsDirectory, SecretInput input, CliOutput output, String... args) {
            return run(secretsDirectory, input, output, null, args);
        }

        /** As above, with the door to the ledger that the command {@code key-register} uses (null: the tool has none). */
        public Outcome run(Path secretsDirectory, SecretInput input, CliOutput output, SessionKeyRegistry registry,
                String... args) {
            List<String> all = new ArrayList<>(List.of(args));
            all.add("--ledger.secrets.directory=" + secretsDirectory);
            all.addAll(extraArguments);
            AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
            ExitCodes exitCodes = new ExitCodes();
            SpringApplicationBuilder builder = new SpringApplicationBuilder(LedgerCliApplication.class)
                    .listeners(exitCodes)
                    .initializers(ctx -> {
                        started.set(ctx);
                        GenericApplicationContext generic = (GenericApplicationContext) ctx;
                        if (database != null) {
                            generic.registerBean("testDataSource", DataSource.class, () -> new DelegatingDataSource(database));
                        }
                        if (legacySource != null) {
                            generic.registerBean("testLegacySource", LegacySourceRepository.class, () -> legacySource);
                        }
                        if (processUser != null) {
                            UserPrincipal principal = () -> processUser;
                            generic.registerBean("testProcessUser", ProcessUser.class, () -> fileSystem -> principal,
                                    bd -> bd.setPrimary(true));
                        }
                        if (input != null) {
                            generic.registerBean("testSecretInput", SecretInput.class, () -> input, bd -> bd.setPrimary(true));
                        }
                        if (registry != null) {
                            generic.registerBean("testSessionKeyRegistry", SessionKeyRegistry.class, () -> registry);
                        }
                        if (output != null) {
                            generic.registerBean("testCliOutput", CliOutput.class, () -> output, bd -> bd.setPrimary(true));
                        }
                    });
            if (database != null) {
                builder.profiles("embedded");
            }
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
