package org.pgledger.cli.deploy;

/**
 * The settings files, Spring Boot properties in YAML, the same on every platform: {@code application.yaml} (what the two
 * services and the owner commands share: the JDBC URL, the account of the application, the schema), and one file per
 * service ({@code mcp.yaml}, {@code admin.yaml}: its port, its allowed hosts, for the admin web where the driver
 * registration is). The paths of the admin web's driver registration follow {@link DeployLayout}. No file holds a secret:
 * the passwords and tokens arrive from the secret store ({@code optional:configtree:} in {@code spring.config.import}).
 */
final class ServiceSettings {

    private ServiceSettings() {
    }

    /** What the services and the owner commands share. */
    static String shared(DeployParameters p) {
        StringBuilder yaml = new StringBuilder();
        Text.line(yaml, Text.GENERATED);
        Text.line(yaml, "# Your own settings (for example ledger.admin.oidc.owners) belong in local.yaml next to this file: it is read after this one and never replaced.");
        Text.line(yaml, "ledger:");
        Text.line(yaml, "  jdbc:");
        Text.line(yaml, "    url: " + Text.quoted(p.jdbcUrl()));
        Text.line(yaml, "    username: " + Text.quoted(p.appAccount()));
        if (p.schema() != null || p.longTextType() != null) {
            Text.line(yaml, "  schema:");
            if (p.schema() != null) {
                Text.line(yaml, "    name: " + Text.quoted(p.schema()));
            }
            if (p.longTextType() != null) {
                Text.line(yaml, "    long-text-type: " + Text.quoted(p.longTextType()));
            }
        }
        return yaml.toString();
    }

    static String mcp(DeployParameters p) {
        StringBuilder yaml = head(p.mcpPort());
        Text.line(yaml, "  mcp:");
        hosts(yaml, p.mcpHost());
        return yaml.toString();
    }

    static String admin(DeployParameters p) {
        StringBuilder yaml = head(p.adminPort());
        Text.line(yaml, "  admin:");
        hosts(yaml, p.adminHost());
        Text.line(yaml, "    driver-file: " + Text.quoted(p.layout().etc() + "/jdbc-driver.yaml"));
        Text.line(yaml, "    driver-directory: " + Text.quoted(p.layout().etc() + "/drivers"));
        Text.line(yaml, "    mcp:");
        Text.line(yaml, "      health-url: " + Text.quoted("http://127.0.0.1:" + p.mcpPort() + "/healthz"));
        return yaml.toString();
    }

    /** The port, up to the key {@code ledger:} (left open for the service). Both servers listen on loopback (their own default). */
    private static StringBuilder head(int port) {
        StringBuilder yaml = new StringBuilder();
        Text.line(yaml, Text.GENERATED);
        Text.line(yaml, "server:");
        Text.line(yaml, "  port: " + port);
        Text.line(yaml, "ledger:");
        return yaml;
    }

    /** The loopback names (the health check and a local call) and the public host name when there is one. */
    private static void hosts(StringBuilder yaml, String publicHost) {
        Text.line(yaml, "    allowed-hosts:");
        Text.line(yaml, "      - '127.0.0.1'");
        Text.line(yaml, "      - 'localhost'");
        if (publicHost != null) {
            Text.line(yaml, "      - " + Text.quoted(publicHost));
        }
    }
}
