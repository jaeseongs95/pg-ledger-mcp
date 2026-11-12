package org.pgledger.cli.deploy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Reads the options every deployment command shares ({@link DeployOptions#ALL}: {@code render-deploy},
 * {@code prepare-host}, {@code seal-host}) and validates them as one: the {@link DeployParameters} (a value record whose
 * Jakarta constraints the validator checks here, once) and the directory the files are put below instead of at their own
 * paths ({@code --root}, a test or a look at the files). Nothing is changed before all of them are valid.
 *
 * <p>A refusal names the option and never the value. When several options are wrong, the one that comes first in
 * {@link DeployOptions#ALL} is named, so the answer does not depend on the order the validator visits them.
 */
@Component
final class DeployInputReader {

    private final Validator validator;
    private final ServicePlatforms platforms;

    DeployInputReader(Validator validator, ServicePlatforms platforms) {
        this.validator = validator;
        this.platforms = platforms;
    }

    /**
     * @throws UsageException MISSING_ARGUMENT for a required option that is absent; INVALID_DEPLOY_VALUE for a value that is
     *         not plain or not possible
     */
    DeployInput read(ApplicationArguments arguments) {
        Map<String, String> values = new HashMap<>();
        for (String option : DeployOptions.ALL) {
            CliOptions.value(arguments, option).ifPresent(value -> values.put(option, value));
        }
        DeployParameters parameters = parameters(values);
        try {
            return new DeployInput(parameters,
                    values.containsKey(DeployOptions.ROOT) ? Path.of(values.get(DeployOptions.ROOT)) : null);
        } catch (InvalidPathException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, DeployOptions.ROOT);
        }
    }

    /**
     * The parameters from the values of the options of the command line.
     *
     * @param values option name to value, only the options that were given
     * @throws UsageException MISSING_ARGUMENT for a required option that is absent; INVALID_DEPLOY_VALUE for a value
     *         that is not plain or not possible
     */
    DeployParameters parameters(Map<String, String> values) {
        Platform platform = platforms.named(values.getOrDefault(DeployOptions.PLATFORM, Platform.LINUX.option()))
                .orElseThrow(() -> new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.PLATFORM));
        DeployLayout layout = platforms.of(platform).layout(
                values.getOrDefault(DeployOptions.NAME, DeployLayout.DEFAULT_NAME), values.get(DeployOptions.HOME));
        String conf = values.get(DeployOptions.NGINX_CONF);
        String certificate = values.get(DeployOptions.TLS_CERTIFICATE);
        String key = values.get(DeployOptions.TLS_KEY);
        DeployParameters.Nginx nginx = null;
        if (conf != null) {
            nginx = new DeployParameters.Nginx(conf, require(certificate), require(key));
        } else if (certificate != null || key != null) {
            // a certificate without a site is a mistake of the command line, not a setting to ignore
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        DeployParameters parameters = new DeployParameters(layout, require(values.get(DeployOptions.JAVA)),
                require(values.get(DeployOptions.JDBC_URL)), require(values.get(DeployOptions.APP_ACCOUNT)),
                values.get(DeployOptions.SCHEMA), values.get(DeployOptions.LONG_TEXT_TYPE),
                number(values.get(DeployOptions.MCP_PORT), DeployParameters.DEFAULT_MCP_PORT, DeployOptions.MCP_PORT),
                number(values.get(DeployOptions.ADMIN_PORT), DeployParameters.DEFAULT_ADMIN_PORT,
                        DeployOptions.ADMIN_PORT),
                values.get(DeployOptions.MCP_HOST), values.get(DeployOptions.ADMIN_HOST), nginx);
        Set<ConstraintViolation<DeployParameters>> violations = validator.validate(parameters);
        if (!violations.isEmpty()) {
            throw refused(violations);
        }
        return parameters;
    }

    private static String require(String value) {
        if (value == null) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        return value;
    }

    private static int number(String text, int fallback, String option) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, option);
        }
    }

    /** The first wrong option in the order of the options; the message of a constraint is the name of its option. */
    private static UsageException refused(Set<ConstraintViolation<DeployParameters>> violations) {
        String option = violations.stream().map(ConstraintViolation::getMessage)
                .min(Comparator.comparingInt(DeployInputReader::rank)).orElseThrow();
        if (rank(option) == Integer.MAX_VALUE) {
            // a constraint without the name of an option is a mistake of the record, and must not put a text of it in a refusal
            throw new IllegalStateException("a constraint of DeployParameters does not name an option");
        }
        return new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, option);
    }

    private static int rank(String option) {
        int index = DeployOptions.ALL.indexOf(option);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
