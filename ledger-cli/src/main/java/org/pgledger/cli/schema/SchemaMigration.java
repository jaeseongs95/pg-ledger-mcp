package org.pgledger.cli.schema;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOptions;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.database.DatabaseBeans;
import org.pgledger.database.schema.GrantApplier;
import org.pgledger.database.schema.SchemaMigrator;
import org.pgledger.database.schema.UtcSessionCheck;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The migration of the schema that {@code migrate} and {@code setup} share: the options of the commands (the DDL account of the
 * run, the password on standard input, the application account that gets the grants) read and checked in one place, and the
 * migration itself, the schema files {@code V{n}__*.sql} with the history table {@code schema_history}, followed by the grant
 * template of the application account when one is given. It is the one path that changes the schema; the server applications
 * only verify it. It does not check the schema first (a database that is not up to date is what it is for).
 */
@Component
public class SchemaMigration {

    /** Read by {@code OwnerAccountEnvironmentPostProcessor}: the run connects as this account, its password is typed. */
    static final String OWNER_ACCOUNT = "owner-account";
    /** Read by {@code OwnerAccountEnvironmentPostProcessor}: the password of the owner account is the next line of standard input. */
    static final String STDIN = "stdin";
    /** The application account (one, shared by the MCP server, the admin web and the owner commands) that gets the grants. */
    static final String APP_ACCOUNT = "app-account";

    /** The options of the commands that migrate. */
    public static final Set<String> OPTIONS = Set.of(OWNER_ACCOUNT, APP_ACCOUNT, STDIN);

    private final ObjectProvider<SchemaMigrator> migrator;
    private final ObjectProvider<GrantApplier> grants;
    private final CliOutput output;
    private final CliMessages messages;

    SchemaMigration(ObjectProvider<SchemaMigrator> migrator, ObjectProvider<GrantApplier> grants, CliOutput output,
            CliMessages messages) {
        this.migrator = migrator;
        this.grants = grants;
        this.output = output;
        this.messages = messages;
    }

    /**
     * Checks the options of the command and returns the application account that gets the grants, if one is given. Nothing is
     * changed here.
     *
     * @throws UsageException for an option without a value, {@code --stdin} without an owner account, or an application
     *         account that is not a plain account name
     */
    public Optional<String> appAccount(ApplicationArguments arguments) {
        // a value without a name of its own would be a blank account: refused here, before anything is changed
        Optional<String> ownerAccount = CliOptions.value(arguments, OWNER_ACCOUNT);
        // --stdin gives the password of the DDL account (the next line of standard input): with no such account it has no use
        if (CliOptions.flag(arguments, STDIN) && ownerAccount.isEmpty()) {
            throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
        }
        // a plain account name only: no SQL is made from anything else
        return CliOptions.value(arguments, APP_ACCOUNT).map(account -> {
            if (!GrantApplier.isAccountName(account)) {
                throw new UsageException(ErrorCode.INVALID_OPTION_VALUE);
            }
            return account;
        });
    }

    /**
     * Applies the pending schema files and prints the versions applied (nothing when the schema is up to date), then, when an
     * application account is given, applies its grants, also when no file was pending: an upgrade that adds a table has to
     * grant it, and granting again changes nothing.
     */
    public void migrate(Optional<String> appAccount) {
        SchemaMigrator target = DatabaseBeans.require(migrator);
        List<Integer> applied;
        try {
            // the history is written in UTC, so the JVM zone is checked like in every other command
            UtcSessionCheck.verifyJvmZone();
            applied = target.migrate();
        } catch (InfrastructureException failed) {
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
        appAccount.ifPresent(this::grant);
    }

    private void grant(String appAccount) {
        GrantApplier applier = DatabaseBeans.require(grants);
        try {
            applier.apply(appAccount);
        } catch (InfrastructureException failed) {
            throw new FailureException(ErrorCode.GRANT_FAILED, failed);
        }
        output.info(messages.get("migrate.granted", appAccount));
    }
}
