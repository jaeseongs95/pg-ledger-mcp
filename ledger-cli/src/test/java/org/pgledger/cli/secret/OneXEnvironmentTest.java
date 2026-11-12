package org.pgledger.cli.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SEC-026: how the environment file of a 1.x installation is read. The first test is the file as the 1.x installer wrote it
 * (commit 9fb62fe, {@code deploy/install.sh}: a heredoc of unquoted {@code KEY=VALUE} lines under a {@code #} comment); the
 * others are what the systemd {@code EnvironmentFile} that the 1.x service read would also accept. The values are made up.
 */
class OneXEnvironmentTest {

    private static final String AGENT = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String ADMIN = "0f9e8d7c6b5a49382716051a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e";

    private static final String AS_WRITTEN_BY_1X = """
            # written by pg-ledger-mcp deploy/install.sh; read by systemd (pg-ledger-mcp.service) and bin/ledger-admin
            PGLEDGER_DB=ledger
            PGLEDGER_PGHOST=127.0.0.1
            PGLEDGER_PGPORT=5432
            PGLEDGER_AUTHOR_PASSWORD=aaaa
            DBHUB_AUTH_TOKEN=%s
            PGLEDGER_ADMIN_TOKEN=%s
            DBHUB_ALLOWED_HOSTS=
            PGLEDGER_ADMIN_TLS_CERT=
            """.formatted(AGENT, ADMIN);

    @Test
    @DisplayName("SEC-026 the file as the 1.x installer wrote it: both tokens are found, an empty setting is no value, a missing one is none")
    void sec026_theFileOfThe1xInstaller() {
        OneXEnvironment environment = OneXEnvironment.parse(AS_WRITTEN_BY_1X);

        assertThat(environment.value("DBHUB_AUTH_TOKEN")).contains(AGENT);
        assertThat(environment.value("PGLEDGER_ADMIN_TOKEN")).contains(ADMIN);
        assertThat(environment.value("DBHUB_ALLOWED_HOSTS")).as("an empty value is no value").isEmpty();
        assertThat(environment.value("NO_SUCH_KEY")).isEmpty();
    }

    @Test
    @DisplayName("SEC-026 the last occurrence of a key wins, as with the 1.x installer (tail -n 1) and with systemd")
    void sec026_theLastOccurrenceWins() {
        OneXEnvironment environment = OneXEnvironment.parse("DBHUB_AUTH_TOKEN=first-value-0123456789\nDBHUB_AUTH_TOKEN=" + AGENT + "\n");

        assertThat(environment.value("DBHUB_AUTH_TOKEN")).contains(AGENT);
    }

    @Test
    @DisplayName("SEC-026 CRLF line ends, blanks around the line and the value, empty lines and the comments # and ; do not change a value")
    void sec026_theSuperset() {
        String text = "\r\n# comment\r\n; another comment\r\n   \r\n  DBHUB_AUTH_TOKEN = " + AGENT + " \r\n#PGLEDGER_ADMIN_TOKEN=" + ADMIN
                + "\r\nPGLEDGER_ADMIN_TOKEN=" + ADMIN;

        OneXEnvironment environment = OneXEnvironment.parse(text);

        assertThat(environment.value("DBHUB_AUTH_TOKEN")).contains(AGENT);
        assertThat(environment.value("PGLEDGER_ADMIN_TOKEN")).as("a commented setting is no setting, the last line has no line end")
                .contains(ADMIN);
    }

    @Test
    @DisplayName("SEC-026 one pair of matching quotes around a value is removed, a lone or mismatched quote stays")
    void sec026_quotes() {
        OneXEnvironment environment = OneXEnvironment.parse(
                "A=\"double\"\nB='single'\nC=\"mismatched'\nD=\"\nE=it's\nF=\"\"\n");

        assertThat(environment.value("A")).contains("double");
        assertThat(environment.value("B")).contains("single");
        assertThat(environment.value("C")).contains("\"mismatched'");
        assertThat(environment.value("D")).contains("\"");
        assertThat(environment.value("E")).contains("it's");
        assertThat(environment.value("F")).as("quotes around nothing are no value").isEmpty();
    }

    @Test
    @DisplayName("SEC-026 a line without a valid name is ignored, not an error; a value may hold =")
    void sec026_otherLines() {
        OneXEnvironment environment = OneXEnvironment.parse("just text\n=no-name\n1BAD=x\nbad name=x\nGOOD=a=b=c\n");

        assertThat(environment.value("GOOD")).contains("a=b=c");
        assertThat(environment.value("1BAD")).isEmpty();
        assertThat(environment.value("bad name")).isEmpty();
        assertThat(environment.value("")).isEmpty();
    }
}
