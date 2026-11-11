package org.pgledger.cli.deploy;

/**
 * The settings file ({@code application.yaml}) of each service: Spring Boot properties, the same on every platform. The
 * paths of the admin web's driver registration follow {@link DeployLayout} (ceiling: a layout for another operating system
 * changes those constants, not this class). No file holds a secret: the passwords and tokens arrive from the secret store
 * of the service ({@code optional:configtree:} in {@code spring.config.import}).
 */
final class ServiceSettings {

    private ServiceSettings() {
    }

    static String mcp(DeployParameters p) {
        StringBuilder yaml = head(p, p.mcpPort(), p.mcpAccount());
        Text.line(yaml, "  mcp:");
        hosts(yaml, p.mcpHost());
        return yaml.toString();
    }

    static String admin(DeployParameters p) {
        StringBuilder yaml = head(p, p.adminPort(), p.adminAccount());
        Text.line(yaml, "  admin:");
        hosts(yaml, p.adminHost());
        Text.line(yaml, "    driver-file: " + Text.quoted(DeployLayout.etc(DeployLayout.ADMIN_NAME) + "/jdbc-driver.yaml"));
        Text.line(yaml, "    driver-directory: " + Text.quoted(DeployLayout.etc(DeployLayout.ADMIN_NAME) + "/drivers"));
        Text.line(yaml, "    mcp:");
        Text.line(yaml, "      health-url: " + Text.quoted("http://127.0.0.1:" + p.mcpPort() + "/healthz"));
        return yaml.toString();
    }

    /** Everything the two settings files share, up to the key {@code ledger:} of the service (left open for it). */
    private static StringBuilder head(DeployParameters p, int port, String account) {
        StringBuilder yaml = new StringBuilder();
        Text.line(yaml, Text.GENERATED);
        Text.line(yaml, "# Your own settings (for example ledger.admin.oidc.owners) belong in local.yaml next to this file: it is read after this one and never replaced.");
        Text.line(yaml, "server:");
        Text.line(yaml, "  port: " + port);
        Text.line(yaml, "ledger:");
        Text.line(yaml, "  jdbc:");
        Text.line(yaml, "    url: " + Text.quoted(p.jdbcUrl()));
        Text.line(yaml, "    username: " + Text.quoted(account));
        if (p.schema() != null || p.longTextType() != null) {
            Text.line(yaml, "  schema:");
            if (p.schema() != null) {
                Text.line(yaml, "    name: " + Text.quoted(p.schema()));
            }
            if (p.longTextType() != null) {
                Text.line(yaml, "    long-text-type: " + Text.quoted(p.longTextType()));
            }
        }
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
