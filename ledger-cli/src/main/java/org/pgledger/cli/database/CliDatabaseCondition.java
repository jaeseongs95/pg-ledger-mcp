package org.pgledger.cli.database;

import org.pgledger.cli.CliCommand;
import org.pgledger.database.driver.DriverRegisteredCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * {@link DriverRegisteredCondition} for the command line tool: the database beans exist only for a command that uses the
 * database. The first operand is the command (Boot's command line property source names the operands this way).
 */
public final class CliDatabaseCondition extends DriverRegisteredCondition {

    @Override
    protected boolean needsDatabase(ConditionContext context) {
        return CliCommand.parse(operands(context).split(",")[0]).filter(command -> !command.usesDatabase()).isEmpty();
    }

    /**
     * The operands as they were given: read from the property sources without resolving placeholders, because an operand may
     * hold {@code ${...}} as text (a name or a description of a code) and {@code Environment#getProperty} would refuse it as an
     * unresolvable placeholder (W7-CLI-02).
     */
    private static String operands(ConditionContext context) {
        if (context.getEnvironment() instanceof ConfigurableEnvironment environment) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                Object operands = source.getProperty(CommandLinePropertySource.DEFAULT_NON_OPTION_ARGS_PROPERTY_NAME);
                if (operands != null) {
                    return operands.toString();
                }
            }
        }
        return "";
    }
}
