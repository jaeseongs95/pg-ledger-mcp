package org.pgledger.cli.schema;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * {@code migrate}: applies the pending schema files {@code V{n}__*.sql} of the database module with its migrator (the history
 * table {@code schema_history} records every attempt) and prints the versions applied; on a database that is up to date it
 * applies nothing. The server applications only verify the schema and never change it, so this is the one command (with
 * {@code setup}, which runs the same path, see {@link SchemaMigration}) that does.
 *
 * <p>{@code --owner-account=<account>} runs it as that database account (the DDL account, the one that may do DDL and
 * grant; its password is typed with hidden input and kept in memory for this run only, see
 * {@code OwnerAccountPropertySource}); the command itself only checks that the option has a value.
 * {@code --app-account=<account>} applies the grant template of the application account ({@code GrantApplier}) after the
 * schema, also when no file was pending, so that a table added by an upgrade is granted. The accounts are created
 * beforehand with the tools of the DBMS: creating an account is not ANSI SQL and not part of the product.
 */
@Component
public class MigrateCommand implements Command {

    private final SchemaMigration migration;

    MigrateCommand(SchemaMigration migration) {
        this.migration = migration;
    }

    @Override
    public CliCommand name() {
        return CliCommand.MIGRATE;
    }

    @Override
    public Set<String> options() {
        return SchemaMigration.OPTIONS;
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Optional<String> appAccount = migration.appAccount(arguments);
        migration.migrate();
        appAccount.ifPresent(migration::grant);
    }
}
