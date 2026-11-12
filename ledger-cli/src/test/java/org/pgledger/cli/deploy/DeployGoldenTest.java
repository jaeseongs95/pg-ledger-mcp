package org.pgledger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.secret.ProcessUser;
import org.pgledger.cli.seed.InstallDefaults;
import org.pgledger.cli.seed.PackagedSeed;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * SET-008: the install defaults moved into the seed bundle, the rendered files did not change. The digests are those of the
 * files the renderer produced for these inputs before the unit SETUP (taken from the code of that commit, with the defaults as
 * constants of the code): every file of the three targets, byte for byte, path and content.
 */
class DeployGoldenTest {

    /** A Linux server with the nginx site. */
    private static Map<String, String> linuxWithSite() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        values.put(DeployOptions.SCHEMA, "ledger");
        values.put(DeployOptions.LONG_TEXT_TYPE, "TEXT");
        values.put(DeployOptions.MCP_HOST, "mcp.example.org");
        values.put(DeployOptions.ADMIN_HOST, "admin.example.org");
        values.put(DeployOptions.NGINX_CONF, "/etc/nginx/conf.d/pg-ledger-mcp.conf");
        values.put(DeployOptions.TLS_CERTIFICATE, "/etc/ssl/ledger/fullchain.pem");
        values.put(DeployOptions.TLS_KEY, "/etc/ssl/ledger/privkey.pem");
        return values;
    }

    /** A Linux server without a site. */
    private static Map<String, String> linuxPlain() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.JAVA, "/usr/lib/jvm/java-25/bin/java");
        values.put(DeployOptions.JDBC_URL, "jdbc:postgresql://db.example.org:5432/ledger");
        values.put(DeployOptions.APP_ACCOUNT, "ledger_app");
        return values;
    }

    /** A personal PC: Windows, no host names. */
    private static Map<String, String> personalPc() {
        Map<String, String> values = new HashMap<>();
        values.put(DeployOptions.PLATFORM, "windows");
        values.put(DeployOptions.HOME, "C:/ProgramData/pg-ledger-mcp");
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

    private static Map<String, Map<String, String>> targets() {
        Map<String, Map<String, String>> targets = new LinkedHashMap<>();
        targets.put("linux with site", linuxWithSite());
        targets.put("linux plain", linuxPlain());
        targets.put("personal pc", personalPc());
        targets.put("windows server", windowsServer());
        return targets;
    }

    /** The SHA-256 of path and content of every rendered file, in the order the renderer gives them. */
    private static String digest(List<RenderedFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (RenderedFile file : files) {
                digest.update((file.path() + "\u0000" + file.content() + "\u0000").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Map<String, String> BEFORE_SETUP = Map.of(
            "linux with site", "205c5052792b4f2c4c9681cb54dba77c52ea7c9a8f1c455c2cb86d6ca1bf1931",
            "linux plain", "a7cc4e0a5d1ff750a4044e9f07bd99bad4bd6b897f8c9fa4779d1bd5ddfbb8e9",
            "personal pc", "09d2be30498adb057b386356b338b0c6be703833cabd16f4f80e0bf5bb595ef1",
            "windows server", "4e1ec84da3f6a9af42fa0b8eeedbec939f80b85458866e8cd8788da2e729ffdf");

    @Test
    @DisplayName("SET-008 the files rendered for the three targets are byte for byte those of the renderer before the unit SETUP (the defaults moved, the files did not)")
    void set008_theRenderedFilesAreUnchanged() {
        Map<String, String> actual = new LinkedHashMap<>();
        targets().forEach((name, values) -> actual.put(name, digest(DeployTestBeans.render(values))));

        assertThat(actual).isEqualTo(BEFORE_SETUP);
    }

    @Test
    @DisplayName("W7-SP-01 the production wiring (the components found by an ApplicationContextRunner: ServicePlatforms, both platforms, the reader and the renderer, no hand assembly) renders the files of SET-008 byte for byte")
    void w7sp01_theProductionWiringRendersTheSameFiles() {
        new ApplicationContextRunner().withBean(ProcessUser.class, () -> fileSystem -> () -> "tester")
                .withBean(Validator.class, () -> Validation.buildDefaultValidatorFactory().getValidator())
                .withUserConfiguration(ServicePlatforms.class, LinuxPlatform.class, WindowsPlatform.class, DeployRenderer.class,
                        DeployInputReader.class, DeployFiles.class, HostFiles.class, WindowsAccess.class,
                        SystemHostAccounts.class, InstallDefaults.class, PackagedSeed.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Map<String, String> actual = new LinkedHashMap<>();
                    targets().forEach((name, values) -> actual.put(name, digest(context.getBean(DeployRenderer.class)
                            .render(context.getBean(DeployInputReader.class).parameters(values)))));

                    assertThat(actual).isEqualTo(BEFORE_SETUP);
                });
    }

    @Test
    @DisplayName("SET-008 giving the name and the two ports of the defaults explicitly renders the same files as leaving them out")
    void set008_explicitDefaultsRenderTheSame() {
        for (Map.Entry<String, Map<String, String>> target : targets().entrySet()) {
            Map<String, String> explicit = new HashMap<>(target.getValue());
            InstallDefaultsHolder.fill(explicit);

            assertThat(DeployTestBeans.render(explicit)).as(target.getKey()).isEqualTo(DeployTestBeans.render(target.getValue()));
        }
    }

    /** The options of the defaults, written out from the install defaults. */
    private static final class InstallDefaultsHolder {

        private static void fill(Map<String, String> values) {
            values.put(DeployOptions.NAME, DeployTestBeans.defaults().instanceName());
            values.put(DeployOptions.MCP_PORT, String.valueOf(DeployTestBeans.defaults().mcpPort()));
            values.put(DeployOptions.ADMIN_PORT, String.valueOf(DeployTestBeans.defaults().adminPort()));
        }
    }
}
