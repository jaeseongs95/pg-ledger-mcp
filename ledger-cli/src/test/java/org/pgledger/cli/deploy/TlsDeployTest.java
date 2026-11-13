package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.cli.CliException;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingHostAccounts;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresPosixPermissions;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * TLS-020 .. TLS-024, TLS-030 .. TLS-033: the automatic certificate in the deployment: the nginx site (own host names only, the
 * proof of HTTP-01, the redirect, no {@code default_server}), the options that go with it, the renewal service and timer, and the
 * directories {@code prepare-host} makes. The site and the units are texts of a pure function of the parameters; the one thing the
 * rendering takes from the host is whether the certificate files exist.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
class TlsDeployTest {

    private static final String MCP = "mcp.example.org";
    private static final String ADMIN = "admin.example.org";
    private static final String CONF = "/etc/nginx/conf.d/pg-ledger-mcp.conf";

    @TempDir
    Path tmp;

    /** A Linux server with the nginx site and the automatic certificate. */
    private static Map<String, String> automatic() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        values.put(DeployOptions.MCP_HOST, MCP);
        values.put(DeployOptions.ADMIN_HOST, ADMIN);
        values.put(DeployOptions.NGINX_CONF, CONF);
        values.put(DeployOptions.TLS_ACME, "");
        return values;
    }

    private static Map<String, String> operator() {
        Map<String, String> values = automatic();
        values.remove(DeployOptions.TLS_ACME);
        values.put(DeployOptions.TLS_CERTIFICATE, "/etc/ssl/ledger/fullchain.pem");
        values.put(DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem");
        return values;
    }

    private static DeployParameters issued(Map<String, String> values, boolean issued) {
        return DeployTestBeans.parameters(values).withCertificateIssued(issued);
    }

    private static String site(DeployParameters parameters) {
        return DeployTestBeans.renderer().render(parameters).stream().filter(file -> CONF.equals(file.path())).findFirst().orElseThrow()
                .content();
    }

    /** FUP-NGX-01/02: the exact port 80 block of a name (changed by FUP-NGX: the block is hardened). */
    static String portEighty(String host, String instance, String webroot) {
        return "server {\n    listen 80;\n    listen [::]:80;\n    server_name " + host + ";\n\n"
                + "    server_tokens off;\n    client_max_body_size 1k;\n    client_header_timeout 10s;\n"
                + "    client_body_timeout 10s;\n    keepalive_timeout 10s;\n"
                + "    limit_req zone=" + instance + "-acme-req burst=20 nodelay;\n    limit_req_status 429;\n"
                + "    limit_conn " + instance + "-acme-conn 20;\n    limit_conn_status 429;\n\n"
                + "    location ~ \"^/\\.well-known/acme-challenge/[A-Za-z0-9_-]+$\" {\n"
                + "        limit_except GET { deny all; }\n        root " + webroot + ";\n        default_type text/plain;\n"
                + "        try_files $uri =404;\n    }\n"
                + "    location /.well-known/acme-challenge/ {\n        return 404;\n    }\n"
                + "    location / {\n        return 301 https://" + host + "$request_uri;\n    }\n}\n";
    }

    private static int count(String text, String fragment) {
        return text.split(Pattern.quote(fragment), -1).length - 1;
    }

    @Test
    @DisplayName("TLS-020 the automatic site: a port 80 block for each own host name (the proof of HTTP-01 from the web root, a redirect to https), and the 443 blocks with the PEM files of the TLS directory; the MCP location keeps proxy_buffering off and the long read timeout")
    void tls020_theSite() {
        String site = site(issued(automatic(), true));

        assertThat(count(site, "server {")).isEqualTo(4);
        for (String host : List.of(MCP, ADMIN)) {
            assertThat(site).contains(portEighty(host, "pg-ledger-mcp", "/var/www/pg-ledger-mcp-acme"));
            assertThat(site).contains("listen 443 ssl;\n    listen [::]:443 ssl;\n    server_name " + host + ";\n");
        }
        assertThat(count(site, "ssl_certificate /etc/pg-ledger-mcp/tls/fullchain.pem;")).isEqualTo(2);
        assertThat(count(site, "ssl_certificate_key /etc/pg-ledger-mcp/tls/privkey.pem;")).isEqualTo(2);
        assertThat(count(site, "proxy_buffering off;")).as("the MCP location only").isEqualTo(1);
        assertThat(count(site, "proxy_read_timeout 3600s;")).isEqualTo(1);
        assertThat(site.indexOf("proxy_buffering off;")).isGreaterThan(site.indexOf("server_name " + MCP + ";\n\n    server_tokens off;\n    ssl_certificate"))
                .isLessThan(site.indexOf("server_name " + ADMIN + ";\n\n    server_tokens off;\n    ssl_certificate"));
    }

    @Test
    @DisplayName("TLS-021 before the certificate files exist the site holds the two port 80 blocks only: nginx can load it and answer the proof")
    void tls021_pendingSite() {
        String site = site(issued(automatic(), false));

        assertThat(count(site, "server {")).isEqualTo(2);
        assertThat(site).doesNotContain("listen 443", "ssl_certificate", "proxy_pass").contains("location /.well-known/acme-challenge/");
    }

    @Test
    @DisplayName("TLS-021 the reader looks at the two certificate files below the root: with both the site holds the 443 blocks, with one or none it does not")
    void tls021_theFilesDecide() throws IOException {
        List<String> args = new ArrayList<>(List.of("--java=/usr/lib/jvm/java-25/bin/java",
                "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app", "--mcp-host=" + MCP,
                "--admin-host=" + ADMIN, "--nginx-conf=" + CONF, "--tls-acme", "--root=" + tmp));
        DeployInputReader reader = DeployTestBeans.reader();
        Path tls = Files.createDirectories(tmp.resolve("etc/pg-ledger-mcp/tls"));

        assertThat(reader.read(new DefaultApplicationArguments(args.toArray(String[]::new))).parameters().nginx().acme()
                .certificateIssued()).isFalse();
        Files.writeString(tls.resolve("fullchain.pem"), "x", StandardCharsets.US_ASCII);
        assertThat(reader.read(new DefaultApplicationArguments(args.toArray(String[]::new))).parameters().nginx().acme()
                .certificateIssued()).as("the chain without the key").isFalse();
        Files.writeString(tls.resolve("privkey.pem"), "x", StandardCharsets.US_ASCII);
        assertThat(reader.read(new DefaultApplicationArguments(args.toArray(String[]::new))).parameters().nginx().acme()
                .certificateIssued()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("sites")
    @DisplayName("TLS-022 whatever the mode, the site holds the server blocks of its own two host names and nothing else: no default_server, no other server_name, no http block, no include, no upstream")
    void tls022_ownNamesOnly(DeployParameters parameters) {
        String site = site(parameters);
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("server_name ([^;]*);").matcher(site);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        assertThat(names).containsExactlyInAnyOrder(MCP, ADMIN);
        assertThat(site).doesNotContain("default_server", "server_name _", "http {", "include ", "upstream ", "worker_", "events {");
        assertThat(count(site, "listen 80;")).isIn(0, 2);
    }

    static List<Arguments> sites() {
        return List.of(Arguments.argumentSet("the operator's certificate", DeployTestBeans.parameters(operator())),
                Arguments.argumentSet("automatic, pending", issued(automatic(), false)),
                Arguments.argumentSet("automatic, issued", issued(automatic(), true)));
    }

    @Test
    @DisplayName("TLS-023 the operator's certificate renders no port 80 block and no renewal unit, and the files are those of before (DeployGoldenTest holds the digests)")
    void tls023_operatorCertificate() {
        List<RenderedFile> files = DeployTestBeans.render(operator());

        assertThat(files.stream().map(RenderedFile::path)).noneMatch(path -> path.contains("tls-renew"));
        assertThat(site(DeployTestBeans.parameters(operator()))).doesNotContain("listen 80", "acme-challenge")
                .contains("ssl_certificate /etc/ssl/ledger/fullchain.pem;");
    }

    @Test
    @DisplayName("FUP-NGX-02 a port 80 block serves the proof file for a token of base64url only (GET and HEAD), answers 404 below the proof path otherwise, redirects the rest to the fixed name, and has no $host, no proxy and no other root")
    void fupNgx02_theLocations() {
        String site = site(issued(automatic(), true));

        for (String host : List.of(MCP, ADMIN)) {
            String block = site.substring(site.indexOf("server_name " + host + ";\n\n    server_tokens off;"));
            block = block.substring(0, block.indexOf("\n}\n"));
            assertThat(block).contains("location ~ \"^/\\.well-known/acme-challenge/[A-Za-z0-9_-]+$\" {\n        limit_except GET { deny all; }")
                    .contains("default_type text/plain;", "try_files $uri =404;")
                    .contains("location /.well-known/acme-challenge/ {\n        return 404;\n    }")
                    .contains("return 301 https://" + host + "$request_uri;")
                    .doesNotContain("$host", "proxy_pass", "autoindex", "alias ");
            assertThat(count(block, "root ")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("FUP-NGX-03 the 443 blocks of the automatic site hide the version; the pending site has the port 80 blocks only; the operator's certificate site has no zone, no limit and no server_tokens")
    void fupNgx03_theOtherBlocks() {
        String issued = site(issued(automatic(), true));
        String pending = site(issued(automatic(), false));
        String operator = site(DeployTestBeans.parameters(operator()));

        assertThat(count(issued, "server_tokens off;")).as("four blocks").isEqualTo(4);
        for (String host : List.of(MCP, ADMIN)) {
            assertThat(issued).contains("    server_name " + host + ";\n\n    server_tokens off;\n    ssl_certificate ");
        }
        assertThat(count(pending, "server_tokens off;")).isEqualTo(2);
        assertThat(operator).doesNotContain("server_tokens", "limit_req", "limit_conn", "client_max_body_size", "keepalive_timeout",
                "_zone ");
    }

    @Test
    @DisplayName("FUP-NGX-04 the two zones of the instance are declared once at the top of the file, with the instance name in their names; another instance has other names; every zone in use is declared; the numbers are explained")
    void fupNgx04_theZones() {
        Map<String, String> other = automatic();
        other.put(DeployOptions.NAME, "ledger-two");
        other.put(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/ledger-two.conf");
        String first = site(issued(automatic(), true));
        String second = DeployTestBeans.renderer().render(issued(other, true)).stream()
                .filter(file -> "/etc/nginx/conf.d/ledger-two.conf".equals(file.path())).findFirst().orElseThrow().content();

        for (String[] site : List.of(new String[] { first, "pg-ledger-mcp" }, new String[] { second, "ledger-two" })) {
            String text = site[0];
            String zones = "limit_req_zone $binary_remote_addr zone=" + site[1] + "-acme-req:1m rate=10r/s;\n"
                    + "limit_conn_zone $binary_remote_addr zone=" + site[1] + "-acme-conn:1m;\n";
            assertThat(count(text, "limit_req_zone ")).isEqualTo(1);
            assertThat(count(text, "limit_conn_zone ")).isEqualTo(1);
            assertThat(text).contains(zones);
            assertThat(text.indexOf(zones)).as("before the first server block").isLessThan(text.indexOf("server {"));
            assertThat(text).contains("# ").contains("each from its own address");
            Matcher used = Pattern.compile("limit_(?:req zone=|conn )([A-Za-z0-9-]+)").matcher(text);
            while (used.find()) {
                assertThat(text).contains("zone=" + used.group(1) + ":");
            }
        }
        assertThat(first).doesNotContain("ledger-two");
        assertThat(second).doesNotContain("pg-ledger-mcp-acme-req");
    }

    private static CliException refused(Map<String, String> values) {
        try {
            DeployTestBeans.parameters(values);
        } catch (CliException e) {
            return e;
        }
        throw new AssertionError("the options were not refused");
    }

    @Test
    @DisplayName("TLS-024 the automatic certificate and the operator's own are not merged; the flag needs the site; Windows has none; a directory needs the flag and is plain")
    void tls024_theOptions() {
        Map<String, String> both = automatic();
        both.put(DeployOptions.TLS_CERTIFICATE, "/etc/ssl/ledger/fullchain.pem");
        Map<String, String> bothKey = automatic();
        bothKey.put(DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem");
        Map<String, String> noSite = automatic();
        noSite.remove(DeployOptions.NGINX_CONF);
        Map<String, String> windows = automatic();
        windows.put(DeployOptions.PLATFORM, "windows");
        windows.put(DeployOptions.HOME, "C:/ProgramData/pg-ledger-mcp");
        windows.put(DeployOptions.JAVA, "C:/Program Files/Java/jdk-25/bin/java.exe");
        Map<String, String> directoryOnly = operator();
        directoryOnly.put(DeployOptions.ACME_DIRECTORY, "https://ca.example.org/dir");

        assertThat(refused(both).code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
        assertThat(refused(both).arguments()).containsExactly(DeployOptions.TLS_CERTIFICATE);
        assertThat(refused(bothKey).arguments()).containsExactly(DeployOptions.TLS_KEY);
        assertThat(refused(noSite).code()).isEqualTo(ErrorCode.MISSING_ARGUMENT);
        assertThat(refused(windows).code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
        assertThat(refused(directoryOnly).arguments()).containsExactly(DeployOptions.ACME_DIRECTORY);
        for (String bad : List.of("https://ca.example.org/a%20b", "https://ca.example.org/$x", "https://ca.example.org/a b",
                "https://ca.example.org/\"x", "http://ca.example.org/dir", "https://user:Secret-Marker-4711@ca.example.org/dir",
                "https://ca.example.org/dir?x=1", "ftp://ca.example.org/dir")) {
            Map<String, String> values = automatic();
            values.put(DeployOptions.ACME_DIRECTORY, bad);
            CliException refused = refused(values);
            assertThat(refused.arguments()).as(bad).containsExactly(DeployOptions.ACME_DIRECTORY);
            assertThat(String.valueOf(refused.getMessage()) + refused.arguments()).doesNotContain("Secret-Marker-4711");
        }
        Map<String, String> good = automatic();
        good.put(DeployOptions.ACME_DIRECTORY, "https://ca.example.org:14000/dir");
        assertThat(DeployTestBeans.parameters(good).nginx().acme().directory()).isEqualTo("https://ca.example.org:14000/dir");
    }

    private static String file(List<RenderedFile> files, String path) {
        return files.stream().filter(f -> path.equals(f.path())).findFirst().orElseThrow(() -> new AssertionError("no file " + path))
                .content();
    }

    @Test
    @DisplayName("TLS-030 with the automatic certificate the renewal service and timer are rendered next to the two units, for the default and for another name, and not without it")
    void tls030_theFiles() {
        List<RenderedFile> files = DeployTestBeans.render(automatic());
        Map<String, String> named = automatic();
        named.put(DeployOptions.NAME, "ledger-two");
        named.put(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/ledger-two.conf");

        assertThat(files.stream().map(RenderedFile::path)).contains("/etc/systemd/system/pg-ledger-mcp.service",
                "/etc/systemd/system/pg-ledger-mcp-admin.service", "/etc/systemd/system/pg-ledger-mcp-tls-renew.service",
                "/etc/systemd/system/pg-ledger-mcp-tls-renew.timer");
        assertThat(DeployTestBeans.render(named).stream().map(RenderedFile::path)).contains(
                "/etc/systemd/system/ledger-two-tls-renew.service", "/etc/systemd/system/ledger-two-tls-renew.timer");
        assertThat(file(DeployTestBeans.render(named), "/etc/systemd/system/ledger-two-tls-renew.service")).contains("ledger-two");
    }

    @Test
    @DisplayName("TLS-031 the renewal service runs tls-renew as the service user, closed in (no capability, no new privilege, writes the TLS directory and the web root only), and reloads nginx with one fixed privileged command: no sudo, no shell, no environment")
    void tls031_theService() {
        String service = file(DeployTestBeans.render(automatic()), "/etc/systemd/system/pg-ledger-mcp-tls-renew.service");
        List<String> lines = service.lines().filter(line -> !line.startsWith("#")).toList();

        assertThat(lines).contains("Type=oneshot", "User=pg-ledger-mcp", "Group=pg-ledger-mcp",
                "ExecStart=/usr/lib/jvm/java-25/bin/java -Duser.timezone=UTC -jar /opt/pg-ledger-mcp/ledger-cli.jar "
                        + "--ledger.secrets.instance=pg-ledger-mcp tls-renew --name=pg-ledger-mcp --mcp-host=" + MCP + " --admin-host=" + ADMIN,
                "ExecStartPost=+/usr/bin/systemctl reload nginx", "NoNewPrivileges=true", "ProtectSystem=strict",
                "ReadWritePaths=/etc/pg-ledger-mcp/tls /var/www/pg-ledger-mcp-acme", "CapabilityBoundingSet=", "UMask=0022",
                "PrivateTmp=true", "After=network-online.target");
        assertThat(lines.stream().filter(line -> line.startsWith("ExecStartPost="))).hasSize(1);
        assertThat(lines.stream().filter(line -> line.startsWith("ReadWritePaths="))).hasSize(1);
        assertThat(service).doesNotContain("sudo", "sh -c", "bash", "Environment=", "--acme-directory", "--agree-tos");
    }

    @Test
    @DisplayName("TLS-031 a certificate authority of the operator is named in the renewal command, and only then")
    void tls031_theDirectory() {
        Map<String, String> values = automatic();
        values.put(DeployOptions.ACME_DIRECTORY, "https://ca.example.org/dir");

        assertThat(file(DeployTestBeans.render(values), "/etc/systemd/system/pg-ledger-mcp-tls-renew.service"))
                .contains(" --admin-host=" + ADMIN + " --acme-directory=https://ca.example.org/dir\n");
    }

    @Test
    @DisplayName("TLS-032 the timer runs twice a day at a random time, makes up a missed run, starts the service of its name and is wanted by timers.target")
    void tls032_theTimer() {
        List<RenderedFile> files = DeployTestBeans.render(automatic());
        List<String> lines = file(files, "/etc/systemd/system/pg-ledger-mcp-tls-renew.timer").lines().filter(l -> !l.startsWith("#")).toList();

        assertThat(lines).contains("OnCalendar=*-*-* 00,12:00:00", "RandomizedDelaySec=3600", "Persistent=true",
                "Unit=pg-ledger-mcp-tls-renew.service", "WantedBy=timers.target");
        for (RenderedFile rendered : files) {
            assertThat(rendered.content().toLowerCase(java.util.Locale.ROOT)).as(rendered.path()).doesNotContain("cloudflare", "lightsail",
                    "route53", "certbot", "zerossl", "buypass");
        }
    }

    private RecordingHostAccounts testUser() throws IOException {
        UserPrincipal user = Files.getOwner(tmp);
        GroupPrincipal group = Files.readAttributes(tmp, PosixFileAttributes.class).group();
        String name = DeployTestBeans.defaults().instanceName();
        return new RecordingHostAccounts(Map.of(name, user), Map.of(name, group));
    }

    private Outcome prepare(Cli cli, String... more) throws IOException {
        Path jars = Files.createDirectories(tmp.resolve("jars"));
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR, "driver.jar")) {
            Files.writeString(jars.resolve(jar), "content of " + jar, StandardCharsets.UTF_8);
        }
        List<String> args = new ArrayList<>(List.of("prepare-host", "--jars=" + jars, "--driver-jar=" + jars.resolve("driver.jar"),
                "--root=" + tmp.resolve("root"), "--java=/usr/lib/jvm/java-25/bin/java",
                "--jdbc-url=jdbc:postgresql://db.example.org:5432/ledger", "--app-account=ledger_app", "--mcp-host=" + MCP,
                "--admin-host=" + ADMIN, "--nginx-conf=" + CONF));
        args.addAll(List.of(more));
        return cli.withHostAccounts(testUser()).run(tmp.resolve("secrets"), null, new RecordingOutput(), args.toArray(String[]::new));
    }

    @Test
    @RequiresPosixPermissions
    @DisplayName("TLS-033 prepare-host --tls-acme makes the TLS directory (rwx------) and the web root (rwxr-xr-x) and writes the units and the port 80 site; without the flag neither directory exists")
    void tls033_prepareHost(Cli cli) throws IOException {
        Outcome plain = prepare(cli, "--tls-certificate=/etc/ssl/a.pem", "--tls-key=/etc/ssl/a.key");
        Path root = tmp.resolve("root");

        assertThat(plain.code()).isEmpty();
        assertThat(root.resolve("etc/pg-ledger-mcp/tls")).doesNotExist();
        assertThat(root.resolve("var/www/pg-ledger-mcp-acme")).doesNotExist();

        Outcome automatic = prepare(cli, "--tls-acme");

        assertThat(automatic.code()).isEmpty();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("etc/pg-ledger-mcp/tls"))))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("var/www/pg-ledger-mcp-acme"))))
                .isEqualTo("rwxr-xr-x");
        assertThat(root.resolve("etc/systemd/system/pg-ledger-mcp-tls-renew.timer")).isRegularFile();
        assertThat(Files.readString(root.resolve("etc/nginx/conf.d/pg-ledger-mcp.conf"))).contains("listen 80;")
                .doesNotContain("listen 443");
    }
}
