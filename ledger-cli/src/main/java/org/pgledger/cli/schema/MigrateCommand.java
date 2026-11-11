package org.pgledger.cli.schema;

import java.util.List;
import java.util.stream.Collectors;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.database.schema.SchemaMigrationException;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.UtcSessionCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * {@code migrate}: applies the pending schema files {@code V{n}__*.sql} of the database module with its
 * {@link SchemaMigrator} (the history table {@code schema_history} records every attempt) and prints the versions applied;
 * on a database that is up to date it applies nothing. The server applications only verify the schema and never change it,
 * so this is the one command that does. It is the one command that does not check the schema first.
 */
@Component
public class MigrateCommand implements Command {

    private final ObjectProvider<SchemaMigrator> migrator;
    private final CliOutput output;
    private final CliMessages messages;

    MigrateCommand(ObjectProvider<SchemaMigrator> migrator, CliOutput output, CliMessages messages) {
        this.migrator = migrator;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.MIGRATE;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        SchemaMigrator target = migrator.getIfAvailable();
        if (target == null) {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        }
        List<Integer> applied;
        try {
            // the history is written in UTC, so the JVM zone is checked like in every other command
            UtcSessionCheck.verifyJvmZone();
            applied = target.migrate();
        } catch (SchemaMigrationException failed) {
            throw new FailureException(ErrorCode.MIGRATION_FAILED, failed, failed.getMessage());
        } catch (DataAccessException failure) {
            throw new FailureException(ErrorCode.DATABASE_FAILED, failure);
        }
        if (applied.isEmpty()) {
            output.info(messages.get("migrate.current"));
        } else {
            output.info(messages.get("migrate.applied", String.valueOf(applied.size()),
                    applied.stream().map(version -> "V" + version).collect(Collectors.joining(", "))));
        }
    }
}
