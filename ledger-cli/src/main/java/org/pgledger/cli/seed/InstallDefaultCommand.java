package org.pgledger.cli.seed;

import java.util.List;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code install-default <name>}: prints one default of the installation ({@link InstallDefaults#NAMES}) on one line and
 * nothing else, so that an installer script can take it (the scripts do not hold the defaults themselves). It needs no
 * database and no driver.
 */
@Component
public class InstallDefaultCommand implements Command {

    private final InstallDefaults defaults;
    private final CliOutput output;

    InstallDefaultCommand(InstallDefaults defaults, CliOutput output) {
        this.defaults = defaults;
        this.output = output;
    }

    @Override
    public CliCommand name() {
        return CliCommand.INSTALL_DEFAULT;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        String name = operands.getFirst();
        if (!InstallDefaults.NAMES.contains(name)) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        output.info(defaults.value(name));
    }
}
