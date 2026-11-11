package org.pgledger.cli.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pgledger.cli.CliOutput;
import org.pgledger.cli.LedgerCliApplication;
import org.pgledger.cli.secret.SecretInput;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** Runs the real application the way main does (SpringApplication.exit), swapping only the input and output boundaries. */
public final class CliHarness {

    public record Result(int exitCode, ConfigurableApplicationContext closedContext) {
    }

    private CliHarness() {
    }

    /** @param input null keeps the product Console input; @param output null keeps the product stream output */
    public static Result run(Path secretsDirectory, SecretInput input, CliOutput output, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--ledger.secrets.directory=" + secretsDirectory);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(LedgerCliApplication.class)
                .initializers(ctx -> {
                    GenericApplicationContext generic = (GenericApplicationContext) ctx;
                    if (input != null) {
                        generic.registerBean("testSecretInput", SecretInput.class, () -> input, bd -> bd.setPrimary(true));
                    }
                    if (output != null) {
                        generic.registerBean("testCliOutput", CliOutput.class, () -> output, bd -> bd.setPrimary(true));
                    }
                })
                .run(all.toArray(String[]::new));
        int exitCode = SpringApplication.exit(context);
        return new Result(exitCode, context);
    }
}
