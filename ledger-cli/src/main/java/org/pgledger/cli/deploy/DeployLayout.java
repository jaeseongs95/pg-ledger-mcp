package org.pgledger.cli.deploy;

/**
 * The fixed names of an installation: the two services (each is an operating system user, a systemd unit, a directory
 * below {@code /etc} and the instance name of {@code ledger.secrets.instance}) and the jars below the installation
 * directory. They are constants and not options so that {@code deploy/install.sh}, which creates the users and the
 * directories, and the files rendered here cannot disagree; a test compares the constants of the script with these.
 */
public final class DeployLayout {

    /** The MCP server: runs as the user of this name, with its own secret directory and database account A. */
    public static final String MCP_NAME = "pg-ledger-mcp";
    /** The admin web and the owner commands of ledger-cli: own user, own secret directory, database account B. */
    public static final String ADMIN_NAME = "pg-ledger-admin";
    public static final String INSTALL_DIR = "/opt/pg-ledger-mcp";
    public static final String MCP_JAR = "ledger-mcp-server.jar";
    public static final String ADMIN_JAR = "ledger-admin-web.jar";
    public static final String CLI_JAR = "ledger-cli.jar";
    /** Where systemd reads the units of the administrator (Spring Boot, "Installing Spring Boot Applications"). */
    public static final String UNIT_DIRECTORY = "/etc/systemd/system";

    private DeployLayout() {
    }

    /** The directory of the settings, the driver registration, the driver jars and the secrets of a service. */
    public static String etc(String service) {
        return "/etc/" + service;
    }
}
