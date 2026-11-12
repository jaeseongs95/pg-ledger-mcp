package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.secret.OneXEnvironment;

/** REH4-HOST-03: which host names of a 1.x environment file the options would drop. */
class LegacyHostsTest {

    private static List<String> dropped(String environment, String key, String... given) {
        return LegacyHosts.dropped(OneXEnvironment.parse(environment), key, List.of(given));
    }

    @Test
    @DisplayName("REH4-HOST-03 a public name is dropped unless an option has it; names are compared in lower case and once, in the order of the file; the loopback names need no option")
    void thePublicNamesAreTheOnesOfTheFile() {
        String key = LegacyHosts.MCP_KEY;

        assertThat(dropped("DBHUB_ALLOWED_HOSTS=ledger-test-mcp.jaeseong.dev,localhost,127.0.0.1\n", key))
                .containsExactly("ledger-test-mcp.jaeseong.dev");
        assertThat(dropped("DBHUB_ALLOWED_HOSTS=ledger-test-mcp.jaeseong.dev,localhost,127.0.0.1\n", key,
                "ledger-test-mcp.jaeseong.dev")).isEmpty();
        assertThat(dropped("DBHUB_ALLOWED_HOSTS= B.Example.org , a.example.org,,b.example.org ,A.EXAMPLE.ORG\n", key))
                .containsExactly("b.example.org", "a.example.org");
        assertThat(dropped("DBHUB_ALLOWED_HOSTS=b.example.org,a.example.org\n", key, "a.example.org"))
                .containsExactly("b.example.org");
        assertThat(dropped("DBHUB_ALLOWED_HOSTS=localhost,127.0.0.1,::1,[::1],LOCALHOST\n", key)).isEmpty();
    }

    @Test
    @DisplayName("REH4-HOST-03 the value is read as the 1.x service read it: quotes, CRLF line ends, the last of two lines; an empty value and an absent key give nothing")
    void theFileIsReadAsTheServiceReadIt() {
        String key = LegacyHosts.MCP_KEY;

        assertThat(dropped("DBHUB_ALLOWED_HOSTS=\"quoted.example.org,localhost\"\r\nOTHER=x\r\n", key))
                .containsExactly("quoted.example.org");
        assertThat(dropped("DBHUB_ALLOWED_HOSTS=first.example.org\nDBHUB_ALLOWED_HOSTS=second.example.org\n", key))
                .containsExactly("second.example.org");
        assertThat(dropped("DBHUB_ALLOWED_HOSTS=\n", key)).isEmpty();
        assertThat(dropped("DBHUB_AUTH_TOKEN=0123456789abcdef0123456789abcdef\n", key)).isEmpty();
        assertThat(dropped("# DBHUB_ALLOWED_HOSTS=commented.example.org\n", key)).isEmpty();
    }

    @Test
    @DisplayName("REH4-HOST-03 a name with a port or a wildcard is not a plain host name and is named too, so that it is never lost without a word")
    void aNameThatIsNotPlainIsNamed() {
        assertThat(dropped("DBHUB_ALLOWED_HOSTS=host.example.org:8765,*.example.org,localhost\n", LegacyHosts.MCP_KEY))
                .containsExactly("host.example.org:8765", "*.example.org");
    }

    @Test
    @DisplayName("REH4-HOST-04 the admin web has its own key of the 1.1.0 environment")
    void theAdminKeyIsItsOwn() {
        String text = "DBHUB_ALLOWED_HOSTS=mcp.example.org\nPGLEDGER_ADMIN_ALLOWED_HOSTS=admin.example.org,127.0.0.1\n";

        assertThat(dropped(text, LegacyHosts.ADMIN_KEY)).containsExactly("admin.example.org");
        assertThat(dropped(text, LegacyHosts.ADMIN_KEY, "admin.example.org")).isEmpty();
        assertThat(dropped(text, LegacyHosts.MCP_KEY, "admin.example.org")).containsExactly("mcp.example.org");
    }
}
