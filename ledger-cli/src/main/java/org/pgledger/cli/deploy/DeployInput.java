package org.pgledger.cli.deploy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;

/**
 * The options every deployment command shares ({@link DeployOptions#ALL}: {@code render-deploy}, {@code prepare-host},
 * {@code seal-host}), read and validated as one: the parameters, and the directory the files are put below instead of at their
 * own paths ({@code --root}, a test or a look at the files). Nothing is changed before all of them are valid.
 *
 * @param parameters the validated parameters
 * @param root       the directory to put everything below, or null to use the real paths
 */
record DeployInput(DeployParameters parameters, Path root) {

    static DeployInput read(ApplicationArguments arguments) {
        Map<String, String> values = new HashMap<>();
        for (String option : DeployOptions.ALL) {
            CliOptions.value(arguments, option).ifPresent(value -> values.put(option, value));
        }
        DeployParameters parameters = DeployParameters.of(values);
        try {
            return new DeployInput(parameters,
                    values.containsKey(DeployOptions.ROOT) ? Path.of(values.get(DeployOptions.ROOT)) : null);
        } catch (InvalidPathException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, DeployOptions.ROOT);
        }
    }
}
