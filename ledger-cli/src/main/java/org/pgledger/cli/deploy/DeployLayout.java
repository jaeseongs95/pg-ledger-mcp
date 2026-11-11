package org.pgledger.cli.deploy;

/**
 * Where an installation puts its files, and the fixed names of it. The product runs as two services, the MCP server and the
 * admin web, under <b>one</b> operating system service account that is not the account of any person: its purpose is to keep
 * the secrets of the ledger (the admin token, the database password) away from the accounts the agents run as, so the secret
 * directory is readable by the service account only. The names are constants and not options so that
 * {@code deploy/install.sh}, {@code deploy/install.cmd}, which create the account's directories, and the files rendered here
 * cannot disagree; a test compares the constants of the scripts with these.
 *
 * <p>Paths are written with {@code /} on both platforms (Java and Spring Boot accept it on Windows, and the text of a file is
 * the same wherever it is rendered).
 *
 * @param platform the operating system family the files are for
 * @param lib      the jars (on Windows also the WinSW executable and configuration of each service)
 * @param etc      the settings, the driver registration, the driver jars and the secret directory
 * @param logs     where the service wrapper writes its logs (Windows; null on Linux, where systemd keeps the journal)
 */
public record DeployLayout(Platform platform, String lib, String etc, String logs) {

    /** The operating system families an installation is rendered for; the one thing a render option decides. */
    public enum Platform {
        LINUX, WINDOWS
    }

    /** The one service account on Linux: a system user of this name (not root). */
    public static final String SERVICE_USER = "pg-ledger-mcp";
    /**
     * The one service account on Windows: the predefined local account LocalService, which "has minimum privileges on the
     * local computer and presents anonymous credentials on the network"
     * (https://learn.microsoft.com/en-us/windows/win32/services/localservice-account). It is no person's account, it needs no
     * password, and the installer can create nothing else without a PowerShell script or {@code net user} (see
     * {@code deploy/install.cmd}).
     */
    public static final String WINDOWS_SERVICE_ACCOUNT = "NT AUTHORITY\\LocalService";
    /** The name of the MCP server service (systemd unit, WinSW service id). */
    public static final String MCP_NAME = "pg-ledger-mcp";
    /** The name of the admin web service. */
    public static final String ADMIN_NAME = "pg-ledger-admin";
    public static final String MCP_JAR = "ledger-mcp-server.jar";
    public static final String ADMIN_JAR = "ledger-admin-web.jar";
    public static final String CLI_JAR = "ledger-cli.jar";
    /** Where systemd reads the units of the administrator (Spring Boot, "Installing Spring Boot Applications"). */
    public static final String UNIT_DIRECTORY = "/etc/systemd/system";

    /** The layout of a Linux installation: jars below {@code /opt}, everything else below {@code /etc}. */
    public static DeployLayout linux() {
        return new DeployLayout(Platform.LINUX, "/opt/pg-ledger-mcp", "/etc/pg-ledger-mcp", null);
    }

    /** The layout of a Windows installation below one directory (for example {@code C:/ProgramData/pg-ledger-mcp}). */
    public static DeployLayout windows(String home) {
        return new DeployLayout(Platform.WINDOWS, home + "/lib", home + "/etc", home + "/logs");
    }
}
