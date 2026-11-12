package org.pgledger.cli.database;

import org.pgledger.cli.CliCommand;
import org.pgledger.database.driver.DriverRegisteredCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.CommandLinePropertySource;

/**
 * {@link DriverRegisteredCondition} for the command line tool: the database beans exist only for a command that uses the
 * database. The first operand is the command (Boot's command line property source names the operands this way).
 */
public final class CliDatabaseCondition extends DriverRegisteredCondition {

    @Override
    protected boolean needsDatabase(ConditionContext context) {
        String operands = context.getEnvironment().getProperty(
                CommandLinePropertySource.DEFAULT_NON_OPTION_ARGS_PROPERTY_NAME, "");
        return CliCommand.parse(operands.split(",")[0]).filter(command -> !command.usesDatabase()).isEmpty();
    }
}
