package org.pgledger.cli.deploy;

/**
 * One of the two services of an installation, as every platform runs it: a name, a jar and the settings file of its own.
 * The arguments of the Java command line are the same on every platform; only the wrapper around them (a systemd unit, a
 * WinSW configuration) differs.
 *
 * @param name         the name of the service (unit name, service id)
 * @param description  what it is
 * @param jar          the jar below {@code lib}
 * @param settingsFile the settings file of this service below {@code etc} (it holds the port and the allowed hosts)
 */
record Service(String name, String description, String jar, String settingsFile) {

    static final Service MCP = new Service(DeployLayout.MCP_NAME, "the ledger MCP server", DeployLayout.MCP_JAR, "mcp.yaml");
    static final Service ADMIN = new Service(DeployLayout.ADMIN_NAME, "the ledger admin web", DeployLayout.ADMIN_JAR,
            "admin.yaml");

    /** {@code -Duser.timezone=UTC -jar <jar> --spring.config.import=<the sources of this service>}. */
    String arguments(DeployLayout layout) {
        return "-Duser.timezone=UTC -jar " + layout.lib() + "/" + jar + " --spring.config.import="
                + DeployRenderer.configImport(layout, settingsFile);
    }
}
