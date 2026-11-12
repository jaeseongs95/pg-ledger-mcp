package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;

/**
 * CLI-PRN-06 (plan 2.6): {@link DeployParameters} is a value record whose rules are Jakarta constraints. The rules themselves
 * are those of INS-019, INS-032 and INS-040 (DeployRendererTest runs every whitelist); this holds what the change to
 * constraints must keep: a refusal names the option and never the value, the message of every constraint is the name of an
 * option, and of several wrong options the first in the order of the options is named.
 */
class DeployParametersTest {

    /** A text that no refusal may show; it is part of a wrong value of each option below. */
    private static final String MARKER = "Secret-Marker-4711";

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Map<String, String> good() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    @Test
    @DisplayName("CLI-PRN-06 a wrong value that holds a secret-looking marker is refused naming the option, and neither the arguments, the message nor the cause chain of the refusal holds the marker")
    void aValueIsNeverEchoed() {
        Map<String, String> wrong = new HashMap<>();
        wrong.put(DeployOptions.NAME, "Name-" + MARKER);
        wrong.put(DeployOptions.JAVA, "/usr/" + MARKER + " x/java");
        wrong.put(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db?x=" + MARKER + "&password=1");
        wrong.put(DeployOptions.APP_ACCOUNT, "acc ount-" + MARKER);
        wrong.put(DeployOptions.SCHEMA, MARKER);
        wrong.put(DeployOptions.LONG_TEXT_TYPE, MARKER + "\n");
        wrong.put(DeployOptions.MCP_HOST, MARKER + ".example.org");
        wrong.put(DeployOptions.ADMIN_HOST, MARKER + ".example.org");

        for (Map.Entry<String, String> entry : wrong.entrySet()) {
            Map<String, String> values = good();
            values.put(entry.getKey(), entry.getValue());

            assertThatThrownBy(() -> DeployTestBeans.parameters(values)).as(entry.getKey())
                    .isInstanceOfSatisfying(UsageException.class, refused -> {
                        assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
                        assertThat(refused.arguments()).containsExactly(entry.getKey());
                        for (Throwable link = refused; link != null; link = link.getCause()) {
                            assertThat(String.valueOf(link.getMessage())).doesNotContain(MARKER);
                        }
                    });
        }
    }

    @Test
    @DisplayName("CLI-PRN-06 when several options are wrong the one that comes first in the order of the options is named, whatever order the validator visits them in")
    void theFirstWrongOptionIsNamed() {
        Map<String, String> values = good();
        values.put(DeployOptions.SCHEMA, "Wrong");
        values.put(DeployOptions.JAVA, "relative/java");
        values.put(DeployOptions.ADMIN_HOST, "-bad");
        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class,
                refused -> assertThat(refused.arguments()).containsExactly(DeployOptions.JAVA));

        values.put(DeployOptions.JAVA, "/usr/bin/java");
        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class,
                refused -> assertThat(refused.arguments()).containsExactly(DeployOptions.SCHEMA));

        values.put(DeployOptions.SCHEMA, "ledger");
        values.put(DeployOptions.MCP_PORT, "9000");
        values.put(DeployOptions.ADMIN_PORT, "9000");
        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class,
                refused -> assertThat(refused.arguments()).containsExactly(DeployOptions.ADMIN_PORT));

        values.put(DeployOptions.ADMIN_PORT, "9001");
        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class,
                refused -> assertThat(refused.arguments()).containsExactly(DeployOptions.ADMIN_HOST));

        values.remove(DeployOptions.ADMIN_HOST);
        assertThat(DeployTestBeans.parameters(values).mcpPort()).isEqualTo(9000);
    }

    @Test
    @DisplayName("CLI-PRN-06 the validator itself: every violation of a record that is wrong in every option has the name of an option as its message, and each such name is among the options; a good record has none")
    void everyConstraintNamesAnOption() {
        DeployParameters good = DeployTestBeans.parameters(good());
        assertThat(VALIDATOR.validate(good)).isEmpty();

        DeployParameters wrong = new DeployParameters(
                DeployLayout.linux("Bad_Name-admin"), "relative", "jdbc:x", "a b", "Wrong", "1TEXT", 80, 80, java.util.List.of("-h"), java.util.List.of("-h"),
                new DeployParameters.Nginx("rel", "rel", "rel"));
        Set<String> messages = new TreeSet<>();
        for (ConstraintViolation<DeployParameters> violation : VALIDATOR.validate(wrong)) {
            messages.add(violation.getMessage());
        }

        assertThat(messages).isSubsetOf(DeployOptions.ALL);
        assertThat(messages).containsExactlyInAnyOrder(DeployOptions.NAME, DeployOptions.JAVA, DeployOptions.JDBC_URL,
                DeployOptions.APP_ACCOUNT, DeployOptions.SCHEMA, DeployOptions.LONG_TEXT_TYPE, DeployOptions.MCP_PORT,
                DeployOptions.ADMIN_PORT, DeployOptions.MCP_HOST, DeployOptions.ADMIN_HOST, DeployOptions.NGINX_CONF,
                DeployOptions.TLS_CERTIFICATE, DeployOptions.TLS_KEY);
    }
}
