package org.pgledger.cli.owner;

import java.util.Arrays;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.secret.SecretInput;
import org.springframework.core.env.PropertySource;

/**
 * The database account and password of one run of the owner commands that change the schema or write the whole ledger
 * ({@code migrate}, {@code import-legacy}): the properties {@code ledger.jdbc.username} and {@code ledger.jdbc.password}
 * in front of every other source, so the one DataSource of the run connects as the owner account and not as the account of
 * the service. The password is typed with hidden input ({@link SecretInput}, which in the product is the console) and lives
 * in memory for this run only: it is never stored, written or printed.
 *
 * <p>The password is asked for when the settings {@code ledger.jdbc.*} are bound (while the application starts, before the
 * command runs), not when the source is made, and it is asked for once, whoever asks and however often. A refusal (no
 * terminal, an empty input) is thrown while that binding happens and so reaches the user as the refusal of the command
 * (see {@code CliFailureAnalyzer}), not as a stack trace. Spring reference:
 * {@link PropertySource} and https://docs.spring.io/spring-boot/reference/features/external-config.html (the order of the
 * property sources).
 */
public final class OwnerAccountPropertySource extends PropertySource<SecretInput> {

    /** The name of the source in the environment. */
    public static final String NAME = "ownerAccount";

    static final String USERNAME_KEY = "ledger.jdbc.username";
    static final String PASSWORD_KEY = "ledger.jdbc.password";

    private final String account;
    private final String prompt;
    private String password;

    /**
     * @param account the owner account
     * @param prompt  the text shown with the hidden input
     * @param input   where the password is read from
     */
    public OwnerAccountPropertySource(String account, String prompt, SecretInput input) {
        super(NAME, input);
        this.account = account;
        this.prompt = prompt;
    }

    @Override
    public Object getProperty(String name) {
        if (USERNAME_KEY.equals(name)) {
            return account;
        }
        if (PASSWORD_KEY.equals(name)) {
            return password();
        }
        return null;
    }

    private synchronized String password() {
        if (password == null) {
            char[] typed = getSource().read(prompt);
            try {
                if (typed == null || typed.length == 0) {
                    throw new FailureException(ErrorCode.EMPTY_SECRET);
                }
                // ceiling: the JDBC driver takes the password as a String, so one String copy lives until the run ends
                password = String.valueOf(typed);
            } finally {
                if (typed != null) {
                    Arrays.fill(typed, '\0');
                }
            }
        }
        return password;
    }

    /** The password never appears in a text made from this source. */
    @Override
    public String toString() {
        return "OwnerAccountPropertySource[account=" + account + ", password=<hidden>]";
    }
}
