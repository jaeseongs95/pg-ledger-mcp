package org.pgledger.database.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Applies the grant templates of {@code db-templates} to a database account: {@code mcp-grants.sql} for the account of the
 * MCP server and {@code admin-grants.sql} for the account of the admin web and the owner commands of the command line tool.
 * The files hold ANSI {@code GRANT} statements only; creating the accounts is a statement of the DBMS and stays an operator
 * step done with the DBMS's own tools before the installation. It is run by the operator's {@code migrate} command through
 * the owner account (the one that may grant), after the schema is applied, and again after every upgrade, so that a table a
 * later schema file adds is granted too. Granting a right twice changes nothing.
 *
 * <p>The account name is the only value that enters the SQL text, so it must be a plain identifier ({@link #isAccountName}):
 * no quoting is needed and no text of the operator can become SQL. The statements run through Spring's
 * {@link ResourceDatabasePopulator}, as the schema files do (see {@link SchemaMigrator}), on a connection that uses the
 * schema of {@link SchemaProperties#name()} when one is configured. Reference:
 * https://docs.spring.io/spring-framework/reference/data-access/jdbc/embedded-database-support.html (script execution).
 */
public final class GrantApplier {

    /** The two sets of rights; each has its own file. */
    public enum Profile {
        MCP("db-templates/mcp-grants.sql"), ADMIN("db-templates/admin-grants.sql");

        private final String resource;

        Profile(String resource) {
            this.resource = resource;
        }

        /** The class path location of the template. */
        public String resource() {
            return resource;
        }
    }

    /** A plain unquoted identifier: a letter, then letters, digits or underscores, at most 30 characters in all. */
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");
    private static final String PLACEHOLDER = "${account}";

    private final DataSource dataSource;
    private final SchemaProperties properties;

    public GrantApplier(DataSource dataSource, SchemaProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /** Whether the text can be an account name in a template; no other text is ever put into the SQL. */
    public static boolean isAccountName(String text) {
        return text != null && ACCOUNT.matcher(text).matches();
    }

    /**
     * Grants the rights of the profile to the account.
     *
     * @throws IllegalArgumentException when the text is not an account name (nothing is run)
     * @throws SchemaMigrationException when the database refuses a statement, for example because the account does not
     *         exist or the connection may not grant; the statements before it stay applied (the next run repeats them)
     */
    public void apply(Profile profile, String account) {
        if (!isAccountName(account)) {
            throw new IllegalArgumentException("not an account name");
        }
        String sql;
        try {
            sql = new ClassPathResource(profile.resource(), GrantApplier.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8).replace(PLACEHOLDER, account);
        } catch (IOException e) {
            throw new UncheckedIOException("the grant template " + profile.resource() + " cannot be read", e);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        try {
            DatabasePopulatorUtils.execute(populator, SchemaDataSource.of(dataSource, properties.name()));
        } catch (DataAccessException e) {
            throw new SchemaMigrationException("cannot apply the grants of the profile " + profile.name()
                    + " to the account (the account must exist and the connection must be allowed to grant)", e);
        }
    }
}
