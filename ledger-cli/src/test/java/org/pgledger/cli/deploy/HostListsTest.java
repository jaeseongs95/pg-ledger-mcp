package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;

/**
 * REH4-OPT-01 to 03: {@code --mcp-host} without {@code --admin-host}, and several public names (a comma list) in the settings of
 * the MCP server, in the nginx site and in the renewal unit. The existing option sets render the same bytes (REH4-OPT-04, the
 * golden digests of {@link DeployGoldenTest}).
 */
class HostListsTest {

    private static final String CONF = "/etc/nginx/conf.d/pg-ledger-mcp.conf";

    private static Map<String, String> plain() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A site with the operator's own certificate. */
    private static Map<String, String> site(String mcp, String admin) {
        Map<String, String> values = plain();
        values.put(DeployOptions.MCP_HOST, mcp);
        if (admin != null) {
            values.put(DeployOptions.ADMIN_HOST, admin);
        }
        values.put(DeployOptions.NGINX_CONF, CONF);
        values.put(DeployOptions.TLS_CERTIFICATE, "/etc/ssl/ledger/fullchain.pem");
        values.put(DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem");
        return values;
    }

    private static Map<String, String> automatic(String mcp, String admin) {
        Map<String, String> values = site(mcp, admin);
        values.remove(DeployOptions.TLS_CERTIFICATE);
        values.remove(DeployOptions.TLS_KEY);
        values.put(DeployOptions.TLS_ACME, "");
        return values;
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> f.path().equals(path)).map(RenderedFile::content).findFirst().orElseThrow();
    }

    private static int count(String text, String fragment) {
        return text.split(Pattern.quote(fragment), -1).length - 1;
    }

    @Test
    @DisplayName("REH4-OPT-01 --mcp-host without --admin-host: the MCP server allows its public name, the admin web keeps the loopback names")
    void opt01_theMcpNameAlone() {
        List<RenderedFile> files = DeployTestBeans.render(Map.of(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java",
                DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger", DeployOptions.APP_ACCOUNT, "ledger_app",
                DeployOptions.MCP_HOST, "mcp.example.org"));

        assertThat(file(files, "/etc/pg-ledger-mcp/mcp.yaml")).contains(
                "    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n      - 'mcp.example.org'\n");
        assertThat(file(files, "/etc/pg-ledger-mcp/admin.yaml")).contains(
                "    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n    driver-file:");
    }

    @Test
    @DisplayName("REH4-OPT-01 with the nginx site: one server block for the MCP name, none for the admin web; with the automatic certificate one port 80 block for the name and the renewal unit names no admin host")
    void opt01_theSiteOfTheMcpNameAlone() {
        String own = file(DeployTestBeans.render(site("mcp.example.org", null)), CONF);
        List<RenderedFile> acme = DeployTestBeans.render(automatic("mcp.example.org", null));
        String renew = file(acme, "/etc/systemd/system/pg-ledger-mcp-tls-renew.service");

        assertThat(count(own, "server {")).isEqualTo(1);
        assertThat(own).contains("    server_name mcp.example.org;\n").contains("proxy_pass http://127.0.0.1:8765;")
                .doesNotContain("8865");
        assertThat(count(file(acme, CONF), "server {")).isEqualTo(1);
        assertThat(renew).contains(" tls-renew --name=pg-ledger-mcp --mcp-host=mcp.example.org\n").doesNotContain("--admin-host");
    }

    @Test
    @DisplayName("REH4-OPT-02 several MCP names: the allowed hosts and the server_name hold the same names in the order of the option; the admin side is as with one name")
    void opt02_severalNames() {
        List<RenderedFile> files = DeployTestBeans.render(site("a.example.org,b.example.org", "admin.example.org"));
        String site = file(files, CONF);

        assertThat(file(files, "/etc/pg-ledger-mcp/mcp.yaml")).contains(
                "    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n      - 'a.example.org'\n      - 'b.example.org'\n");
        assertThat(file(files, "/etc/pg-ledger-mcp/admin.yaml")).contains(
                "    allowed-hosts:\n      - '127.0.0.1'\n      - 'localhost'\n      - 'admin.example.org'\n");
        assertThat(count(site, "server {")).isEqualTo(2);
        assertThat(site).contains("    server_name a.example.org b.example.org;\n").contains("    server_name admin.example.org;\n");
    }

    @Test
    @DisplayName("REH4-OPT-02 with the automatic certificate: a port 80 block for each name that redirects to that name, the 443 block of the MCP names once the files exist, and the renewal unit with the list")
    void opt02_severalNamesWithAcme() {
        List<RenderedFile> pending = DeployTestBeans.renderer().render(
                DeployTestBeans.parameters(automatic("a.example.org,b.example.org", "admin.example.org")).withCertificateIssued(false));
        List<RenderedFile> issued = DeployTestBeans.renderer().render(
                DeployTestBeans.parameters(automatic("a.example.org,b.example.org", "admin.example.org")).withCertificateIssued(true));

        assertThat(count(file(pending, CONF), "server {")).isEqualTo(3);
        assertThat(count(file(issued, CONF), "server {")).isEqualTo(5);
        for (String host : List.of("a.example.org", "b.example.org", "admin.example.org")) {
            assertThat(file(issued, CONF)).contains("    server_name " + host + ";\n\n    server_tokens off;")
                    .contains("    location /.well-known/acme-challenge/ {\n        return 404;")
                    .contains("return 301 https://" + host + "$request_uri;");
        }
        assertThat(file(issued, CONF)).contains("listen 443 ssl;\n    listen [::]:443 ssl;\n    server_name a.example.org b.example.org;\n");
        assertThat(file(issued, "/etc/systemd/system/pg-ledger-mcp-tls-renew.service")).contains(
                " tls-renew --name=pg-ledger-mcp --mcp-host=a.example.org,b.example.org --admin-host=admin.example.org\n");
    }

    @Test
    @DisplayName("REH4-OPT-03 ten names are accepted")
    void opt03_tenNames() {
        String ten = String.join(",", Stream.iterate(1, n -> n + 1).limit(10).map(n -> "h" + n + ".example.org").toList());

        assertThat(file(DeployTestBeans.render(site(ten, null)), "/etc/pg-ledger-mcp/mcp.yaml")).contains("      - 'h10.example.org'\n");
    }

    static Stream<Arguments> badLists() {
        String eleven = String.join(",", Stream.iterate(1, n -> n + 1).limit(11).map(n -> "h" + n + ".example.org").toList());
        return Stream.of(
                Arguments.of(DeployOptions.MCP_HOST, "a.example.org,,b.example.org"), Arguments.of(DeployOptions.MCP_HOST, "a.example.org,"),
                Arguments.of(DeployOptions.MCP_HOST, ",a.example.org"), Arguments.of(DeployOptions.MCP_HOST, "a.example.org,a.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "A.example.org,b.example.org"), Arguments.of(DeployOptions.MCP_HOST, "a.example.org, b.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "a.example.org,b.example.org:443"), Arguments.of(DeployOptions.MCP_HOST, eleven),
                Arguments.of(DeployOptions.MCP_HOST, "a.example.org," + "x".repeat(254)),
                Arguments.of(DeployOptions.ADMIN_HOST, "admin.example.org,a.example.org"),
                Arguments.of(DeployOptions.ADMIN_HOST, "b.example.org,admin.example.org,b.example.org"),
                Arguments.of(DeployOptions.ADMIN_HOST, "admin.example.org,"));
    }

    @ParameterizedTest
    @MethodSource("badLists")
    @DisplayName("REH4-OPT-03 an empty entry, a name twice, a name that is not plain, more than ten names, a name longer than 253 characters, and a name of both options are refused naming the option, never the value")
    void opt03_badLists(String option, String value) {
        Map<String, String> values = site("a.example.org,c.example.org", "admin.example.org");
        values.put(option, value);

        assertThatThrownBy(() -> DeployTestBeans.parameters(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(option);
        });
    }

    @Test
    @DisplayName("REH4-OPT-03 the site needs a MCP name (the one name every site serves); the admin name is optional")
    void opt03_theSiteNeedsTheMcpName() {
        Map<String, String> noMcp = site("mcp.example.org", "admin.example.org");
        noMcp.remove(DeployOptions.MCP_HOST);

        assertThatThrownBy(() -> DeployTestBeans.parameters(noMcp)).isInstanceOfSatisfying(UsageException.class,
                e -> assertThat(e.arguments()).containsExactly(DeployOptions.MCP_HOST));
        assertThat(DeployTestBeans.parameters(site("mcp.example.org", null)).nginx()).isNotNull();
    }
}
