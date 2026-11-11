package org.pgledger.cli.schema;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.pgledger.cli.CliCommand;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.Command;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.database.schema.GrantApplier;
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
 *
 * <p>{@code --owner-account=<account>} runs it as that database account (the account that may do DDL and grant; its
 * password is typed with hidden input and kept in memory for this run only, see {@code OwnerAccountPropertySource}); the
 * command itself only checks that the option has a value. {@code --mcp-account=<account> --admin-account=<account>}
 * (both or none) apply the grant templates of the MCP server and of the admin web ({@link GrantApplier}) after the
 * schema, also when no file was pending, so that a table added by an upgrade is granted. The accounts are created
 * beforehand with the tools of the DBMS: creating an account is not ANSI SQL and not part of the product.
 */
@Component
public class MigrateCommand implements Command {

    /** Read by {@code OwnerAccountEnvironmentPostProcessor}: the run connects as this account, its password is typed. */
    static final String OWNER_ACCOUNT = "owner-account";
    static final String MCP_ACCOUNT = "mcp-account";
    static final String ADMIN_ACCOUNT = "admin-account";

    private final ObjectProvider<SchemaMigrator> migrator;
    private final ObjectProvider<GrantApplier> grants;
    private final CliOutput output;
    private final CliMessages messages;

    MigrateCommand(ObjectProvider<SchemaMigrator> migrator, ObjectProvider<GrantApplier> grants, CliOutput output,
            CliMessages messages) {
        this.migrator = migrator;
        this.grants = grants;
        this.output = output;
        this.messages = messages;
    }

    @Override
    public CliCommand name() {
        return CliCommand.MIGRATE;
    }

    @Override
    public Set<String> options() {
        return Set.of(OWNER_ACCOUNT, MCP_ACCOUNT, ADMIN_ACCOUNT);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (!operands.isEmpty()) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        // a value without a name of its own would be a blank account: refused here, before anything is changed
        CliOptions.value(arguments, OWNER_ACCOUNT);
        Optional<String> mcpAccount = CliOptions.value(arguments, MCP_ACCOUNT);
        Optional<String> adminAccount = CliOptions.value(arguments, ADMIN_ACCOUNT);
        if (mcpAccount.isPresent() != adminAccount.isPresent()) {
            // the grants are applied for both applications or for none: one account alone would leave the other unprepared
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (mcpAccount.isPresent() && !accountsAreUsable(mcpAccount.get(), adminAccount.get())) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
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
        if (mcpAccount.isPresent()) {
            grant(mcpAccount.get(), adminAccount.get());
        }
    }

    /**
     * Applies the grant templates of the two applications after the schema, also when nothing was migrated: an upgrade that
     * adds a table has to grant it, and granting again changes nothing.
     */
    private void grant(String mcpAccount, String adminAccount) {
        GrantApplier applier = grants.getIfAvailable();
        if (applier == null) {
            throw new FailureException(ErrorCode.DRIVER_NOT_CONFIGURED);
        }
        try {
            applier.apply(GrantApplier.Profile.MCP, mcpAccount);
            applier.apply(GrantApplier.Profile.ADMIN, adminAccount);
        } catch (SchemaMigrationException failed) {
            throw new FailureException(ErrorCode.GRANT_FAILED, failed);
        }
        output.info(messages.get("migrate.granted", mcpAccount, adminAccount));
    }

    /** Both are plain account names (no SQL is made from anything else) and they are two accounts, not one. */
    private static boolean accountsAreUsable(String mcpAccount, String adminAccount) {
        return GrantApplier.isAccountName(mcpAccount) && GrantApplier.isAccountName(adminAccount)
                && !mcpAccount.equalsIgnoreCase(adminAccount);
    }
}
