package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * INS-015 .. INS-019, INS-030 and INS-032 (Windows): the files of the deployment are rendered from parameters as text, with
 * no root, no Linux, no Windows and no file access. The three targets (a personal PC, a Windows server, a Linux server) are
 * three sets of parameters.
 */
class DeployRendererTest {

    private static final String MCP_UNIT = "/etc/systemd/system/pg-ledger-mcp.service";
    private static final String ADMIN_UNIT = "/etc/systemd/system/pg-ledger-admin.service";
    private static final String HOME = "C:/ProgramData/pg-ledger-mcp";

    /** A Linux server. */
    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A Linux server with the nginx site. */
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

    /** A personal PC: Windows, no host names. */
    private static Map<String, String> personalPc() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.PLATFORM, "windows");
        values.put(DeployOptions.HOME, HOME);
        values.put(DeployOptions.JAVA, "C:/Program Files/Java/jdk-25/bin/java.exe");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://localhost:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A Windows server: the same, with the public host names of the reverse proxy the operator put in front. */
    private static Map<String, String> windowsServer() {
        Map<String, String> values = personalPc();
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        return values;
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> f.path().equals(path)).map(RenderedFile::content).findFirst().orElseThrow();
    }

    private static List<RenderedFile> render(Map<String, String> values) {
        return DeployRenderer.render(DeployParameters.of(values));
    }

    // ---- INS-015: the Linux units ----

    @ParameterizedTest
    @MethodSource("units")
    @DisplayName("INS-015 each unit runs as the one service user, never root, with the hardening set and its own jar and settings file")
    void ins015_theUnitsAreHardenedAndNotRoot(String path, String jar, String settingsFile, String readWritePaths) {
        String unit = file(render(withSite()), path);

        assertThat(unit.lines().toList()).contains("User=pg-ledger-mcp", "Group=pg-ledger-mcp", "NoNewPrivileges=true",
                "ProtectSystem=strict", "ProtectHome=true", "PrivateTmp=true", "PrivateDevices=true",
                "ProtectKernelTunables=true", "ProtectKernelModules=true", "ProtectControlGroups=true",
                "RestrictSUIDSGID=true", "LockPersonality=true", "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
                "CapabilityBoundingSet=", "UMask=0077", "SuccessExitStatus=143", "Restart=on-failure");
        assertThat(unit).doesNotContain("User=root", "Group=root");
        assertThat(unit.lines().filter(line -> line.startsWith("ReadWritePaths=")).toList())
                .isEqualTo(readWritePaths.isEmpty() ? List.of() : List.of("ReadWritePaths=" + readWritePaths));
        String start = unit.lines().filter(line -> line.startsWith("ExecStart=")).findFirst().orElseThrow();
        assertThat(start).isEqualTo("ExecStart=/usr/lib/jvm/java-25/bin/java -Duser.timezone=UTC -jar /opt/pg-ledger-mcp/" + jar
                + " --spring.config.import=file:/etc/pg-ledger-mcp/application.yaml,file:/etc/pg-ledger-mcp/" + settingsFile
                + ",optional:file:/etc/pg-ledger-mcp/local.yaml,optional:file:/etc/pg-ledger-mcp/jdbc-driver.yaml,"
                + "optional:configtree:/etc/pg-ledger-mcp/secrets/");
    }

    static Stream<Arguments> units() {
        return Stream.of(Arguments.of(MCP_UNIT, "ledger-mcp-server.jar", "mcp.yaml", ""),
                Arguments.of(ADMIN_UNIT, "ledger-admin-web.jar", "admin.yaml", "/etc/pg-ledger-mcp"));
    }

    // ---- INS-016: the settings ----

    /** The settings file as Spring Boot reads it: the same loader, so the file parses as YAML and its keys are the flat keys of the properties. */
    private static EnumerablePropertySource<?> yaml(String text) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("rendered",
                new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        return (EnumerablePropertySource<?>) sources.getFirst();
    }

    @Test
    @DisplayName("INS-016 the shared settings hold the JDBC URL and the one application account; each service file holds its port and hosts; no secret")
    void ins016_theSettings() throws IOException {
        List<RenderedFile> files = render(withSite());

        EnumerablePropertySource<?> shared = yaml(file(files, "/etc/pg-ledger-mcp/application.yaml"));
        assertThat(shared.getProperty("ledger.jdbc.url")).isEqualTo("jdbc:postgresql://db.example.org:5432/ledger");
        assertThat(shared.getProperty("ledger.jdbc.username")).isEqualTo("ledger_app");
        assertThat(shared.getProperty("ledger.schema.name")).isEqualTo("ledger");
        assertThat(shared.getProperty("ledger.schema.long-text-type")).isEqualTo("TEXT");

        EnumerablePropertySource<?> mcp = yaml(file(files, "/etc/pg-ledger-mcp/mcp.yaml"));
        assertThat(mcp.getProperty("server.port")).isEqualTo(8765);
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[0]")).isEqualTo("127.0.0.1");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[1]")).isEqualTo("localhost");
        assertThat(mcp.getProperty("ledger.mcp.allowed-hosts[2]")).isEqualTo("mcp.example.org");

        EnumerablePropertySource<?> admin = yaml(file(files, "/etc/pg-ledger-mcp/admin.yaml"));
        assertThat(admin.getProperty("server.port")).isEqualTo(8865);
        assertThat(admin.getProperty("ledger.admin.allowed-hosts[2]")).isEqualTo("admin.example.org");
        assertThat(admin.getProperty("ledger.admin.driver-file")).isEqualTo("/etc/pg-ledger-mcp/jdbc-driver.yaml");
        assertThat(admin.getProperty("ledger.admin.driver-directory")).isEqualTo("/etc/pg-ledger-mcp/drivers");
        assertThat(admin.getProperty("ledger.admin.mcp.health-url")).isEqualTo("http://127.0.0.1:8765/healthz");
        for (EnumerablePropertySource<?> settings : List.of(shared, mcp, admin)) {
            assertThat(List.of(settings.getPropertyNames())).noneMatch(name -> name.matches("(?i).*(password|token|secret).*"));
        }
    }

    @Test
    @DisplayName("INS-016 the schema keys are written only when they are given; the loopback hosts are the only ones without host names")
    void ins016_optionalKeysAreAbsentWhenNotGiven() throws IOException {
        List<RenderedFile> files = render(values());

        assertThat(List.of(yaml(file(files, "/etc/pg-ledger-mcp/application.yaml")).getPropertyNames()))
                .containsExactlyInAnyOrder("ledger.jdbc.url", "ledger.jdbc.username");
        assertThat(List.of(yaml(file(files, "/etc/pg-ledger-mcp/mcp.yaml")).getPropertyNames())).containsExactlyInAnyOrder(
                "server.port", "ledger.mcp.allowed-hosts[0]", "ledger.mcp.allowed-hosts[1]");
    }

    // ---- INS-017: the nginx site ----

    @Test
    @DisplayName("INS-017 the nginx site overwrites X-Forwarded-For with the connection address, sets Host, proxies to loopback and has no automation of a certificate")
    void ins017_theNginxSite() {
        String site = file(render(withSite()), "/etc/nginx/conf.d/pg-ledger-mcp.conf");

        assertThat(site).contains("server_name mcp.example.org;", "server_name admin.example.org;",
                "ssl_certificate /etc/ssl/ledger/fullchain.pem;", "ssl_certificate_key /etc/ssl/ledger/privkey.pem;",
                "proxy_pass http://127.0.0.1:8765;", "proxy_pass http://127.0.0.1:8865;");
        assertThat(site.lines().filter(line -> "proxy_set_header X-Forwarded-For $remote_addr;".equals(line.strip())).count())
                .isEqualTo(2);
        assertThat(site.lines().filter(line -> "proxy_set_header Host $host;".equals(line.strip())).count()).isEqualTo(2);
        assertThat(site.toLowerCase(Locale.ROOT)).doesNotContain("$proxy_add_x_forwarded_for", "x-forwarded-host",
                "cloudflare", "certbot", "acme", "x-real-ip");
        assertThat(site.lines().filter(line -> line.strip().startsWith("proxy_set_header X-Forwarded-For")).toList())
                .allMatch(line -> line.strip().endsWith("$remote_addr;"));
    }

    @Test
    @DisplayName("INS-017 without the nginx parameters no site is rendered (a Linux server may also run without a proxy)")
    void ins017_noSiteWithoutTheParameters() {
        assertThat(render(values()).stream().map(RenderedFile::path)).containsExactly(MCP_UNIT, ADMIN_UNIT,
                "/etc/pg-ledger-mcp/application.yaml", "/etc/pg-ledger-mcp/mcp.yaml", "/etc/pg-ledger-mcp/admin.yaml");
        assertThat(render(withSite()).stream().map(RenderedFile::path)).endsWith("/etc/nginx/conf.d/pg-ledger-mcp.conf");
    }

    // ---- INS-018 ----

    @ParameterizedTest
    @MethodSource("targets")
    @DisplayName("INS-018 the rendering is deterministic: the same text every time, line feeds only, one trailing line feed, absolute paths")
    void ins018_isDeterministic(String target, Map<String, String> values) {
        List<RenderedFile> first = render(values);
        List<RenderedFile> second = render(values);

        assertThat(second).as(target).isEqualTo(first);
        assertThat(first).allSatisfy(file -> {
            assertThat(file.content()).doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n");
            assertThat(file.path()).matches("(/|[A-Za-z]:/).*");
        });
    }

    static Stream<Arguments> targets() {
        return Stream.of(Arguments.of("Linux server", withSite()), Arguments.of("personal PC", personalPc()),
                Arguments.of("Windows server", windowsServer()));
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
                Arguments.of(DeployOptions.APP_ACCOUNT, "a b"), Arguments.of(DeployOptions.APP_ACCOUNT, "a;drop"),
                Arguments.of(DeployOptions.APP_ACCOUNT, "1abc"),
                Arguments.of(DeployOptions.SCHEMA, "Ledger"), Arguments.of(DeployOptions.SCHEMA, "led-ger"),
                Arguments.of(DeployOptions.LONG_TEXT_TYPE, "TEXT\nx"),
                Arguments.of(DeployOptions.MCP_PORT, "80"), Arguments.of(DeployOptions.MCP_PORT, "70000"),
                Arguments.of(DeployOptions.MCP_PORT, "abc"), Arguments.of(DeployOptions.ADMIN_PORT, "8765"),
                Arguments.of(DeployOptions.MCP_HOST, "Mcp.Example.org"), Arguments.of(DeployOptions.MCP_HOST, "mcp_host.example.org"),
                Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org/x"), Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org:443"),
                Arguments.of(DeployOptions.MCP_HOST, "-mcp.example.org"), Arguments.of(DeployOptions.MCP_HOST, "mcp.example.org\nx"),
                Arguments.of(DeployOptions.ADMIN_HOST, "mcp.example.org"),
                Arguments.of(DeployOptions.TLS_CERTIFICATE, "relative/cert.pem"), Arguments.of(DeployOptions.TLS_KEY, "/etc/ssl/key;x"),
                Arguments.of(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/../x.conf"),
                Arguments.of(DeployOptions.PLATFORM, "macos"), Arguments.of(DeployOptions.PLATFORM, "Linux"),
                Arguments.of(DeployOptions.HOME, "/opt/x"));
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
        });
    }

    static Stream<Arguments> unsafeWindowsValues() {
        return Stream.of(
                Arguments.of(DeployOptions.HOME, "/opt/x"), Arguments.of(DeployOptions.HOME, "C:/Program Files/x"),
                Arguments.of(DeployOptions.HOME, "C:\\ProgramData\\x"), Arguments.of(DeployOptions.HOME, "C:/x/../y"),
                Arguments.of(DeployOptions.HOME, "C:/x$y"), Arguments.of(DeployOptions.HOME, "C:"),
                Arguments.of(DeployOptions.JAVA, "/usr/bin/java"), Arguments.of(DeployOptions.JAVA, "C:/Program Files/<x>/java.exe"),
                Arguments.of(DeployOptions.JAVA, "C:/Program Files/a&b/java.exe"), Arguments.of(DeployOptions.JAVA, "C:/a\"b/java.exe"),
                Arguments.of(DeployOptions.JAVA, "C:/a\nb/java.exe"),
                Arguments.of(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/x.conf"));
    }

    @ParameterizedTest
    @MethodSource("unsafeWindowsValues")
    @DisplayName("INS-032 on Windows the home and the Java path are refused when they are not plain drive paths (a space is allowed only in the Java path), and nginx is not part of a Windows installation")
    void ins032_unsafeWindowsValuesAreRefused(String option, String value) {
        Map<String, String> values = personalPc();
        values.put(option, value);
        if (DeployOptions.NGINX_CONF.equals(option)) {
            values.put(DeployOptions.TLS_CERTIFICATE, "/etc/ssl/c.pem");
            values.put(DeployOptions.TLS_KEY, "/etc/ssl/k.pem");
        }

        assertThatThrownBy(() -> DeployParameters.of(values)).isInstanceOfSatisfying(UsageException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
            assertThat(e.arguments()).containsExactly(option);
        });
    }

    @Test
    @DisplayName("INS-019 the nginx site needs both host names, a certificate and a key; a certificate without a site is refused; a missing required option is MISSING_ARGUMENT; Windows needs its home")
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

        for (String required : List.of(DeployOptions.JAVA, DeployOptions.JDBC_URL, DeployOptions.APP_ACCOUNT)) {
            Map<String, String> missing = values();
            missing.remove(required);
            assertThatThrownBy(() -> DeployParameters.of(missing)).isInstanceOfSatisfying(UsageException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));
        }

        Map<String, String> noHome = personalPc();
        noHome.remove(DeployOptions.HOME);
        assertThatThrownBy(() -> DeployParameters.of(noHome)).isInstanceOfSatisfying(UsageException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.MISSING_ARGUMENT));
    }

    // ---- INS-030 ----

    @Test
    @DisplayName("INS-030 the command line tool imports for the installation the four sources the units import (without the file of a service), and nothing else")
    void ins030_theToolAndTheServicesReadTheSameSources() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", true);
        String imported = helper.replacePlaceholders(properties.getProperty("spring.config.import"),
                placeholder -> null);
        String unitImport = file(render(values()), MCP_UNIT).lines().filter(line -> line.startsWith("ExecStart="))
                .findFirst().orElseThrow().replaceFirst(".*--spring\\.config\\.import=", "");

        List<String> expected = List.of(unitImport.split(",")).stream().filter(source -> !source.endsWith("/mcp.yaml"))
                .map(source -> source.startsWith("optional:") ? source : "optional:" + source).toList();
        assertThat(List.of(imported.split(","))).containsExactlyElementsOf(expected);
        // the placeholder carries the home: the Windows installation gives its own on the command line
        String windows = helper.replacePlaceholders(properties.getProperty("spring.config.import"),
                placeholder -> "ledger.secrets.home".equals(placeholder) ? HOME + "/etc" : null);
        assertThat(windows).contains("optional:file:" + HOME + "/etc/application.yaml",
                "optional:configtree:" + HOME + "/etc/secrets/");
    }

    // ---- INS-032: Windows ----

    private static Document xml(String text) throws ParserConfigurationException, IOException, SAXException {
        return DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String element(Document document, String name) {
        return document.getElementsByTagName(name).item(0).getTextContent();
    }

    @ParameterizedTest
    @MethodSource("windowsServices")
    @DisplayName("INS-032 each WinSW configuration is well-formed XML with the id of its service, the Java of the parameters, the same arguments as the Linux unit, a restart after a failure and a log folder; it names no account")
    void ins032_theWinswConfiguration(String id, String jar, String settingsFile) throws Exception {
        List<RenderedFile> files = render(personalPc());
        Document configuration = xml(file(files, HOME + "/lib/" + id + ".xml"));

        assertThat(configuration.getDocumentElement().getTagName()).isEqualTo("service");
        assertThat(element(configuration, "id")).isEqualTo(id);
        assertThat(element(configuration, "name")).isEqualTo(id);
        assertThat(element(configuration, "executable")).isEqualTo("C:\\Program Files\\Java\\jdk-25\\bin\\java.exe");
        assertThat(element(configuration, "arguments")).isEqualTo("-Duser.timezone=UTC -jar " + HOME + "/lib/" + jar
                + " --spring.config.import=file:" + HOME + "/etc/application.yaml,file:" + HOME + "/etc/" + settingsFile
                + ",optional:file:" + HOME + "/etc/local.yaml,optional:file:" + HOME + "/etc/jdbc-driver.yaml,"
                + "optional:configtree:" + HOME + "/etc/secrets/");
        assertThat(element(configuration, "logpath")).isEqualTo("C:\\ProgramData\\pg-ledger-mcp\\logs");
        assertThat(element(configuration, "startmode")).isEqualTo("Automatic");
        assertThat(configuration.getElementsByTagName("onfailure").getLength()).isPositive();
        assertThat(configuration.getElementsByTagName("onfailure").item(0).getAttributes().getNamedItem("action")
                .getNodeValue()).isEqualTo("restart");
        assertThat(configuration.getElementsByTagName("serviceaccount").getLength())
                .as("the one account is set by deploy/install.cmd with sc.exe config").isZero();
    }

    static Stream<Arguments> windowsServices() {
        return Stream.of(Arguments.of("pg-ledger-mcp", "ledger-mcp-server.jar", "mcp.yaml"),
                Arguments.of("pg-ledger-admin", "ledger-admin-web.jar", "admin.yaml"));
    }

    @Test
    @DisplayName("INS-032 a personal PC renders the two WinSW configurations and the settings below its home, no unit, no proxy; its hosts are loopback only")
    void ins032_thePersonalPc() throws IOException {
        List<RenderedFile> files = render(personalPc());

        assertThat(files.stream().map(RenderedFile::path)).containsExactly(HOME + "/lib/pg-ledger-mcp.xml",
                HOME + "/lib/pg-ledger-admin.xml", HOME + "/etc/application.yaml", HOME + "/etc/mcp.yaml",
                HOME + "/etc/admin.yaml");
        EnumerablePropertySource<?> admin = yaml(file(files, HOME + "/etc/admin.yaml"));
        assertThat(admin.getProperty("ledger.admin.driver-file")).isEqualTo(HOME + "/etc/jdbc-driver.yaml");
        assertThat(admin.getProperty("ledger.admin.driver-directory")).isEqualTo(HOME + "/etc/drivers");
        assertThat(List.of(yaml(file(files, HOME + "/etc/mcp.yaml")).getPropertyNames())).containsExactlyInAnyOrder(
                "server.port", "ledger.mcp.allowed-hosts[0]", "ledger.mcp.allowed-hosts[1]");
        assertThat(files).noneMatch(f -> f.content().contains("User=") || f.content().contains("nginx"));
    }

    @Test
    @DisplayName("INS-032 a Windows server adds its public host names to the allowed hosts; the reverse proxy in front of it is the operator's own")
    void ins032_theWindowsServer() throws IOException {
        List<RenderedFile> files = render(windowsServer());

        assertThat(yaml(file(files, HOME + "/etc/mcp.yaml")).getProperty("ledger.mcp.allowed-hosts[2]"))
                .isEqualTo("mcp.example.org");
        assertThat(yaml(file(files, HOME + "/etc/admin.yaml")).getProperty("ledger.admin.allowed-hosts[2]"))
                .isEqualTo("admin.example.org");
        assertThat(files.stream().map(RenderedFile::path)).noneMatch(path -> path.contains("nginx"));
    }
}
