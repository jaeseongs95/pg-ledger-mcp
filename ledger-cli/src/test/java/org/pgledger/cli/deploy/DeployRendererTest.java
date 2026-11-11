package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * INS-015 .. INS-019 and INS-030: the files of the deployment are rendered from parameters as text, with no root, no Linux
 * and no file access.
 */
class DeployRendererTest {

    private static final String MCP_UNIT = "/etc/systemd/system/pg-ledger-mcp.service";
    private static final String ADMIN_UNIT = "/etc/systemd/system/pg-ledger-admin.service";

    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.MCP_ACCOUNT, "ledger_mcp");
        values.put(DeployOptions.ADMIN_ACCOUNT, "ledger_admin");
        return values;
    }

    private static Map<String, String> withSite() {
        Map<String, String> values = values();
        values.put(DeployOptions.SCHEMA, "ledger");
        values.put(DeployOptions.LONG_TEXT_TYPE, "TEXT");
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        values.put(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/pg-ledger-mcp.conf");
        values.put(DeployOptions.TLS_CERTIFICATE, "/etc/ssl/ledger/fullchain.pem");
        values.put(DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem");
        return values;
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> f.path().equals(path)).map(RenderedFile::content).findFirst().orElseThrow();
    }

    private static List<RenderedFile> render(Map<String, String> values) {
        return DeployRenderer.render(DeployParameters.of(values));
    }

    // ---- INS-015 ----

    @ParameterizedTest
    @MethodSource("units")
    @DisplayName("INS-015 each unit runs as its own account, never root, with the hardening set and its own jar and secret directory")
    void ins015_theUnitsAreHardenedAndNotRoot(String path, String service, String jar, String readWritePaths) {
        String unit = file(render(withSite()), path);

        assertThat(unit.lines().toList()).contains("User=" + service, "Group=" + service, "NoNewPrivileges=true",
                "ProtectSystem=strict", "ProtectHome=true", "PrivateTmp=true", "PrivateDevices=true",
                "ProtectKernelTunables=true", "ProtectKernelModules=true", "ProtectControlGroups=true",
                "RestrictSUIDSGID=true", "LockPersonality=true", "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
                "CapabilityBoundingSet=", "UMask=0077", "SuccessExitStatus=143", "Restart=on-failure");
        assertThat(unit).doesNotContain("User=root", "Group=root");
        assertThat(unit.lines().filter(line -> line.startsWith("ReadWritePaths=")).toList())
                .isEqualTo(readWritePaths.isEmpty() ? List.of() : List.of("ReadWritePaths=" + readWritePaths));
        String start = unit.lines().filter(line -> line.startsWith("ExecStart=")).findFirst().orElseThrow();
        assertThat(start).isEqualTo("ExecStart=/usr/lib/jvm/java-25/bin/java -Duser.timezone=UTC -jar /opt/pg-ledger-mcp/" + jar
                + " --spring.config.import=" + DeployRenderer.configImport(service));
        String other = service.equals(DeployLayout.MCP_NAME) ? DeployLayout.ADMIN_NAME : DeployLayout.MCP_NAME;
        assertThat(start).as("a service never reads the directory of the other").doesNotContain(other);
    }

    static Stream<Arguments> units() {
        return Stream.of(Arguments.of(MCP_UNIT, "pg-ledger-mcp", "ledger-mcp-server.jar", ""),
                Arguments.of(ADMIN_UNIT, "pg-ledger-admin", "ledger-admin-web.jar", "/etc/pg-ledger-admin"));
    }

    // ---- INS-016 ----

    /** The settings file as Spring Boot reads it: the same loader, so the file parses as YAML and its keys are the flat keys of the properties. */
    private static EnumerablePropertySource<?> yaml(String text) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("rendered",
                new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }

    @Test
    @DisplayName("INS-016 each settings file holds the JDBC URL and the account of its own service, the port, the hosts and no secret")
    void ins016_theSettingsOfEachService() throws IOException {
        List<RenderedFile> files = render(withSite());

        EnumerablePropertySource<?> mcp = yaml(file(files, "/etc/pg-ledger-mcp/application.yaml"));
        assertThat(mcp.getProperty("ledger.jdbc.url")).isEqualTo("jdbc:postgresql://db.example.org:5432/ledger");
        assertThat(mcp.getProperty("ledger.jdbc.username")).isEqualTo("ledger_mcp");
        assertThat(mcp.getProperty("server.port")).isEqualTo(8765);
        assertThat(mcp.getProperty("ledger.schema.name")).isEqualTo("ledger");
        assertThat(mcp.getProperty("ledger.schema.long-text-type")).isEqualTo("TEXT");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[0]")).isEqualTo("127.0.0.1");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[1]")).isEqualTo("localhost");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[2]")).isEqualTo("mcp.example.org");

        EnumerablePropertySource<?> admin = yaml(file(files, "/etc/pg-ledger-admin/application.yaml"));
        assertThat(admin.getProperty("ledger.jdbc.username")).isEqualTo("ledger_admin");
        assertThat(admin.getProperty("server.port")).isEqualTo(8865);
        assertThat(admin.getProperty("ledger.admin.allowed-hosts[2]")).isEqualTo("admin.example.org");
        assertThat(admin.getProperty("ledger.admin.driver-file")).isEqualTo("/etc/pg-ledger-admin/jdbc-driver.yaml");
        assertThat(admin.getProperty("ledger.admin.driver-directory")).isEqualTo("/etc/pg-ledger-admin/drivers");
        assertThat(admin.getProperty("ledger.admin.mcp.health-url")).isEqualTo("http://127.0.0.1:8765/healthz");
        for (EnumerablePropertySource<?> settings : List.of(mcp, admin)) {
            assertThat(List.of(settings.getPropertyNames())).noneMatch(name -> name.matches("(?i).*(password|token|secret).*"));
        }
    }

    @Test
    @DisplayName("INS-016 the schema keys are written only when they are given")
    void ins016_optionalKeysAreAbsentWhenNotGiven() throws IOException {
        EnumerablePropertySource<?> mcp = yaml(file(render(values()), "/etc/pg-ledger-mcp/application.yaml"));

        assertThat(List.of(mcp.getPropertyNames())).noneMatch(name -> name.startsWith("ledger.schema"));
        assertThat(List.of(mcp.getPropertyNames())).containsExactlyInAnyOrder("server.port", "ledger.jdbc.url",
                "ledger.jdbc.username", "ledger.mcp.allowed-hosts[0]", "ledger.mcp.allowed-hosts[1]");
    }

    // ---- INS-017 ----

    @Test
    @DisplayName("INS-017 the nginx site overwrites X-Forwarded-For with the connection address, sets Host, proxies to loopback and has no automation of a certificate")
    void ins017_theNginxSite() {
        String site = file(render(withSite()), "/etc/nginx/conf.d/pg-ledger-mcp.conf");

        assertThat(site).contains("server_name mcp.example.org;", "server_name admin.example.org;",
                "ssl_certificate /etc/ssl/ledger/fullchain.pem;", "ssl_certificate_key /etc/ssl/ledger/privkey.pem;",
                "proxy_pass http://127.0.0.1:8765;", "proxy_pass http://127.0.0.1:8865;");
        assertThat(site.lines().filter(line -> line.strip().equals("proxy_set_header X-Forwarded-For $remote_addr;")).count())
                .isEqualTo(2);
        assertThat(site.lines().filter(line -> line.strip().equals("proxy_set_header Host $host;")).count()).isEqualTo(2);
        assertThat(site.toLowerCase(java.util.Locale.ROOT)).doesNotContain("$proxy_add_x_forwarded_for", "x-forwarded-host",
                "cloudflare", "certbot", "acme", "x-real-ip");
        assertThat(site.lines().filter(line -> line.strip().startsWith("proxy_set_header X-Forwarded-For")).toList())
                .allMatch(line -> line.strip().endsWith("$remote_addr;"));
    }

    @Test
    @DisplayName("INS-017 without the nginx parameters no site is rendered")
    void ins017_noSiteWithoutTheParameters() {
        assertThat(render(values()).stream().map(RenderedFile::path)).containsExactly(MCP_UNIT, ADMIN_UNIT,
                "/etc/pg-ledger-mcp/application.yaml", "/etc/pg-ledger-admin/application.yaml");
        assertThat(render(withSite()).stream().map(RenderedFile::path)).endsWith("/etc/nginx/conf.d/pg-ledger-mcp.conf");
    }

    // ---- INS-018 ----

    @Test
    @DisplayName("INS-018 the rendering is deterministic: the same text every time, line feeds only, one trailing line feed")
    void ins018_isDeterministic() {
        List<RenderedFile> first = render(withSite());
        List<RenderedFile> second = render(withSite());

        assertThat(second).isEqualTo(first);
        assertThat(first).allSatisfy(file -> {
            assertThat(file.content()).doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n");
            assertThat(file.path()).startsWith("/");
        });
    }

    // ---- INS-019 ----

    static Stream<Arguments> unsafeValues() {
        String twoLines = "/usr/bin/java\nUser=root";
        return Stream.of(
                Arguments.of(DeployOptions.JAVA, "usr/bin/java"), Arguments.of(DeployOptions.JAVA, "/usr/bin/../bin/java"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/j ava"), Arguments.of(DeployOptions.JAVA, twoLines),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/java$HOME"), Arguments.of(DeployOptions.JAVA, "/usr/%h/java"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/'java'"), Arguments.of(DeployOptions.JAVA, "/usr/bin/./java"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin\\java"), Arguments.of(DeployOptions.JAVA, "/"),
                Arguments.of(DeployOptions.JDBC_URL, "postgresql://h/db"), Arguments.of(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db?password=x"),
                Arguments.of(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db?PassWord=x"),
                Arguments.of(DeployOptions.JDBC_URL, "jdbc:postgresql://h/db'\nx: y"), Arguments.of(DeployOptions.JDBC_URL, "jdbc:p://h/${x}"),
                Arguments.of(DeployOptions.JDBC_URL, "jdbc:p://h/ db"),
                Arguments.of(DeployOptions.MCP_ACCOUNT, "a b"), Arguments.of(DeployOptions.MCP_ACCOUNT, "a;drop"),
                Arguments.of(DeployOptions.MCP_ACCOUNT, "1abc"), Arguments.of(DeployOptions.ADMIN_ACCOUNT, "ledger_mcp"),
                Arguments.of(DeployOptions.ADMIN_ACCOUNT, "LEDGER_MCP"),
                Arguments.of(DeployOptions.SCHEMA, "Ledger"), Arguments.of(DeployOptions.SCHEMA, "led-ger"),
                Arguments.of(DeployOptions.LONG_TEXT_TYPE, "TEXT\nx"),
                Arguments.of(DeployOptions.MCP_PORT, "80"), Arguments.of(DeployOptions.MCP_PORT, "70000"),
                Arguments.of(DeployOptions.MCP_PORT, "abc"), Arguments.of(DeployOptions.ADMIN_PORT, "8765"),
                Arguments.of(DeployOptions.MCP_HOST, "Mcp.Example.org"), Arguments.of(DeployOptions.MCP_HOST, "mcp_host.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org/x"), Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org:443"),
                Arguments.of(DeployOptions.MCP_HOST, "-mcp.example.org"), Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org\nx"),
                Arguments.of(DeployOptions.ADMIN_HOST, "mcp.example.org"),
                Arguments.of(DeployOptions.TLS_CERTIFICATE, "relative/cert.pem"), Arguments.of(DeployOptions.TLS_KEY, "/etc/ssl/key;x"),
                Arguments.of(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/../x.conf"));
    }

    @ParameterizedTest
    @MethodSource("unsafeValues")
    @DisplayName("INS-019 an unsafe value is refused with the name of the option and never the value")
    void ins019_unsafeValuesAreRefused(String option, String value) {
        Map<String, String> values = withSite();
        values.put(option, value);

        assertThatThrownBy(() -> DeployParameters.of(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(option);
            assertThat(String.valueOf(e.getMessage())).doesNotContain(value);
        });
    }

    @Test
    @DisplayName("INS-019 the nginx site needs both host names, a certificate and a key; a certificate without a site is refused; a missing required option is MISSING_ARGUMENT")
    void ins019_incompleteParametersAreRefused() {
        Map<String, String> noHost = withSite();
        noHost.remove(DeployOptions.ADMIN_HOST);
        assertThatThrownBy(() -> DeployParameters.of(noHost)).isInstanceOfSatisfying(UsageException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE));

        Map<String, String> noKey = withSite();
        noKey.remove(DeployOptions.TLS_KEY);
        assertThatThrownBy(() -> DeployParameters.of(noKey)).isInstanceOfSatisfying(UsageException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));

        Map<String, String> orphan = values();
        orphan.put(DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem");
        assertThatThrownBy(() -> DeployParameters.of(orphan)).isInstanceOfSatisfying(UsageException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));

        for (String required : List.of(DeployOptions.JAVA, DeployOptions.JDBC_URL, DeployOptions.MCP_ACCOUNT,
                DeployOptions.ADMIN_ACCOUNT)) {
            Map<String, String> missing = values();
            missing.remove(required);
            assertThatThrownBy(() -> DeployParameters.of(missing)).isInstanceOfSatisfying(UsageException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));
        }
    }

    // ---- INS-030 ----

    @ParameterizedTest
    @MethodSource("services")
    @DisplayName("INS-030 the command line tool imports for an instance the four sources the unit of that service imports, and nothing of the other service")
    void ins030_theToolAndTheServiceReadTheSameSources(String service, String unitPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", true);
        String imported = helper.replacePlaceholders(properties.getProperty("spring.config.import"),
                placeholder -> placeholder.equals("ledger.secrets.instance") ? service : null);
        String unitImport = file(render(values()), unitPath).lines().filter(line -> line.startsWith("ExecStart="))
                .findFirst().orElseThrow().replaceFirst(".*--spring\\.config\\.import=", "");

        assertThat(List.of(imported.split(","))).containsExactlyElementsOf(List.of(unitImport.split(",")).stream()
                .map(source -> source.startsWith("optional:") ? source : "optional:" + source).toList());
        assertThat(imported).doesNotContain(service.equals(DeployLayout.MCP_NAME) ? DeployLayout.ADMIN_NAME : DeployLayout.MCP_NAME);
    }

    static Stream<Arguments> services() {
        return Stream.of(Arguments.of(DeployLayout.MCP_NAME, MCP_UNIT), Arguments.of(DeployLayout.ADMIN_NAME, ADMIN_UNIT));
    }
}
