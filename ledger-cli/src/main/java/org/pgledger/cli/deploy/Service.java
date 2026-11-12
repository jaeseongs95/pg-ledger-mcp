package org.pgledger.cli.deploy;

/**
 * One of the two services of an instance, as every platform runs it: a name, a jar and the settings file of its own. The
 * arguments of the Java command line are the same on every platform; only the wrapper around them (a systemd unit, a WinSW
 * configuration) differs.
 *
 * @param name         the name of the service (unit name, service id)
 * @param description  what it is
 * @param jar          the jar below {@code lib}
 * @param settingsFile the settings file of this service below {@code etc} (it holds the port and the allowed hosts)
 */
record Service(String name, String description, String jar, String settingsFile) {

    static Service mcp(DeployLayout layout) {
        return new Service(layout.mcpName(), "the ledger MCP server", DeployLayout.MCP_JAR, "mcp.yaml");
    }

    static Service admin(DeployLayout layout) {
        return new Service(layout.adminName(), "the ledger admin web", DeployLayout.ADMIN_JAR, "admin.yaml");
    }

    /** {@code -Duser.timezone=UTC -jar <jar> --spring.config.import=<the sources of this service>}. */
    String arguments(DeployLayout layout) {
        return "-Duser.timezone=UTC -jar " + layout.lib() + "/" + jar + " --spring.config.import="
                + DeployRenderer.configImport(layout, settingsFile);
    }
}
