package org.pgledger.database.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Applies the grant template {@code db-templates/dml-grants.sql} to the application account: the account of the MCP server,
 * the admin web and the owner commands of the command line tool (they share one). The file holds ANSI {@code GRANT}
 * statements only (no DDL right, no {@code UPDATE} or {@code DELETE} on the history); creating the account is a statement
 * of the DBMS and stays an operator step done with the DBMS's own tools before the installation. It is run by the
 * operator's {@code migrate} command through the DDL account (the one that may grant), after the schema is applied and
 * again after every upgrade, so that a table a later schema file adds is granted too. Granting a right twice changes nothing.
 *
 * <p>The account name is the only value that enters the SQL text, so it must be a plain identifier ({@link #isAccountName}):
 * no quoting is needed and no text of the operator can become SQL. The statements run through Spring's
 * {@link ResourceDatabasePopulator}, as the schema files do (see {@link SchemaMigrator}), on a connection that uses the
 * schema of {@link SchemaProperties#name()} when one is configured. Reference:
 * https://docs.spring.io/spring-framework/reference/data-access/jdbc/embedded-database-support.html (script execution).
 */
@Component
public class GrantApplier {

    /** The class path location of the template. */
    public static final String TEMPLATE = "db-templates/dml-grants.sql";

    /** A plain unquoted identifier: a letter, then letters, digits or underscores, at most 30 characters in all. */
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");
    private static final String PLACEHOLDER = "${dml_account}";

    private final DataSource dataSource;
    private final SchemaProperties properties;

    public GrantApplier(DataSource dataSource, SchemaProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /** Whether the text can be an account name in the template; no other text is ever put into the SQL. */
    public static boolean isAccountName(String text) {
        return text != null && ACCOUNT.matcher(text).matches();
    }

    /**
     * Grants the rights of the template to the account.
     *
     * @throws IllegalArgumentException when the text is not an account name (nothing is run)
     * @throws InfrastructureException SCHEMA_GRANT_FAILED when the database refuses a statement, for example because the
     *         account does not exist or the connection may not grant; the statements before it stay applied (the next run
     *         repeats them)
     */
    public void apply(String account) {
        if (!isAccountName(account)) {
            throw new IllegalArgumentException("not an account name");
        }
        String sql;
        try {
            sql = new ClassPathResource(TEMPLATE, GrantApplier.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8).replace(PLACEHOLDER, account);
        } catch (IOException e) {
            throw new UncheckedIOException("the grant template " + TEMPLATE + " cannot be read", e);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, "UTF-8",
                new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        try {
            DatabasePopulatorUtils.execute(populator, SchemaDataSource.of(dataSource, properties.name()));
        } catch (DataAccessException e) {
            // the account must exist and the connection must be allowed to grant
            throw new InfrastructureException(InfrastructureCode.SCHEMA_GRANT_FAILED, List.of(), e);
        }
    }
}
