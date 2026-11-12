package org.pgledger.cli.deploy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;

/**
 * Where an installation puts its files, and the names of it. An installation is an <em>instance</em> with a name (the default
 * is an install default, see {@code InstallDefaults}): the product runs as two services, the MCP server (named {@code <name>}) and the admin web (named
 * {@code <name>-admin}), under <b>one</b> operating system service account that belongs to no person (on Linux the system user
 * {@code <name>}). Its purpose is to keep the secrets of the ledger (the admin token, the database password) away from the
 * accounts the agents run as, so the secret directory is readable by the service account only. Everything an instance owns
 * (the service names, the user, the jar directory, the settings and secret directory, the WinSW ids) follows the name, so two
 * instances can live on one host. The jar names are constants, and so is the rule that makes the name; a name is only accepted
 * through {@link DeployParameters}. {@code deploy/install.sh} and {@code deploy/install.cmd}, which create the directories,
 * compose the same paths from the name, and a test compares them with these.
 *
 * <p>Paths are written with {@code /} on both platforms (Java and Spring Boot accept it on Windows, and the text of a file is
 * the same wherever it is rendered).
 *
 * @param platform the operating system family the files are for
 * @param name     the name of the instance (already validated)
 * @param lib      the jars (on Windows also the WinSW executable and configuration of each service)
 * @param etc      the settings, the driver registration, the driver jars and the secret directory
 * @param logs     where the service wrapper writes its logs (Windows; null on Linux, where systemd keeps the journal)
 */
public record DeployLayout(Platform platform,
        @Pattern(regexp = DeployForms.NAME, message = DeployOptions.NAME) String name, String lib, String etc,
        String logs) {

    /**
     * The operating system families an installation is rendered for; the one thing a render option decides. What each family
     * does (its service definitions, the host preparation) is its {@code ServicePlatform}; this holds what is plain data of it.
     */
    public enum Platform {
        /** A Linux server: systemd units; may have the nginx site. */
        LINUX(DeployForms.POSIX_PATH, true),
        /** A personal PC or a Windows server: WinSW services; nginx is not part of it. */
        WINDOWS(DeployForms.WINDOWS_JAVA, false);

        private final String javaPathForm;
        private final boolean servesNginx;

        Platform(String javaPathForm, boolean servesNginx) {
            this.javaPathForm = javaPathForm;
            this.servesNginx = servesNginx;
        }

        /** The value of {@code --platform} that names it. */
        public String option() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The form of the path of the Java runtime on this platform (a regular expression). */
        String javaPathForm() {
            return javaPathForm;
        }

        /** Whether an installation may have the nginx site. */
        boolean servesNginx() {
            return servesNginx;
        }
    }

    /**
     * The one service account on Windows: the predefined local account LocalService, which "has minimum privileges on the
     * local computer and presents anonymous credentials on the network"
     * (https://learn.microsoft.com/en-us/windows/win32/services/localservice-account). It is no person's account, it needs no
     * password, and the installer can create nothing else without a PowerShell script or {@code net user} (see
     * {@code deploy/install.cmd}). It is shared by every instance of the host and by every other service that runs as it.
     */
    public static final String WINDOWS_SERVICE_ACCOUNT = "NT AUTHORITY\\LocalService";
    public static final String MCP_JAR = "ledger-mcp-server.jar";
    public static final String ADMIN_JAR = "ledger-admin-web.jar";
    public static final String CLI_JAR = "ledger-cli.jar";
    /** Where systemd reads the units of the administrator (Spring Boot, "Installing Spring Boot Applications"). */
    public static final String UNIT_DIRECTORY = "/etc/systemd/system";

    /** The layout of a Linux instance: jars below {@code /opt/<name>}, everything else below {@code /etc/<name>}. */
    public static DeployLayout linux(String name) {
        return new DeployLayout(Platform.LINUX, name, "/opt/" + name, "/etc/" + name, null);
    }

    /** The layout of a Windows instance below one directory (for example {@code C:/ProgramData/<name>}). */
    public static DeployLayout windows(String name, String home) {
        return new DeployLayout(Platform.WINDOWS, name, home + "/lib", home + "/etc", home + "/logs");
    }

    /** The name of the MCP server service (systemd unit, WinSW service id). */
    public String mcpName() {
        return name;
    }

    /** The name of the admin web service. */
    public String adminName() {
        return name + "-admin";
    }

    /**
     * Linux: where {@code tls-issue} and {@code tls-renew} keep the account key and the certificate files by default
     * ({@code account.key}, {@code fullchain.pem}, {@code privkey.pem}): below the settings directory, which is the service
     * user's, so the keys are the service user's.
     */
    public String tlsDirectory() {
        return etc + "/tls";
    }

    /** The names of the files in the TLS directory: the account key, the certificate chain and the key of the certificate. */
    public static final String TLS_ACCOUNT_FILE = "account.key";
    public static final String TLS_CHAIN_FILE = "fullchain.pem";
    public static final String TLS_KEY_FILE = "privkey.pem";

    /**
     * Linux: the web root the port 80 blocks of the nginx site serve {@code /.well-known/acme-challenge/} from, and where
     * {@code tls-issue} writes the token files. It is outside the settings directory (which the service user alone may enter)
     * because the account the web server runs as has to read the tokens.
     */
    public String acmeWebroot() {
        return "/var/www/" + name + "-acme";
    }

    /** Linux: the name of the systemd service and timer that renew the certificate. */
    public String tlsRenewName() {
        return name + "-tls-renew";
    }

    /** The one service account on Linux: the system user of the name of the instance (no login, not root). */
    public String serviceUser() {
        return name;
    }

    /** A name that ends with {@code -admin} would collide with the admin service of the instance without that ending. */
    @AssertTrue(message = DeployOptions.NAME)
    public boolean isNotAnAdminName() {
        return name == null || !name.endsWith("-admin");
    }
}
