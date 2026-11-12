package org.pgledger.cli.deploy;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.secret.OneXEnvironment;

/**
 * The public host names of a 1.x installation. The clients of a 1.x server reach it through the names its environment file
 * allows ({@code DBHUB_ALLOWED_HOSTS} for the MCP server; {@code PGLEDGER_ADMIN_ALLOWED_HOSTS} for the admin web of 1.1.0), and the
 * Host check of 2.x refuses every other name, so a name that the options of the installation do not carry would break every client
 * right after the migration. {@code --from-1x-env=<file>} lets {@code render-deploy}, {@code prepare-host} and {@code seal-host}
 * compare the names of the file with {@code --mcp-host} and {@code --admin-host} and refuse before anything is changed
 * ({@code LEGACY_HOSTS_DROPPED}, naming the host names, which are no secret, and the option to add them to). Nothing else of the
 * file is used or kept: the rendered files stay a function of the options alone, so a later run without the file renders the same
 * files. The command is run by root, which can read the file of root ({@code deploy/install.sh} checks that it is readable).
 */
final class LegacyHosts {

    /** The setting of 1.x that names the hosts the MCP server answers to. */
    static final String MCP_KEY = "DBHUB_ALLOWED_HOSTS";
    /** The setting of 1.1.0 that names the hosts the admin web answers to (1.0.0 had no admin web). */
    static final String ADMIN_KEY = "PGLEDGER_ADMIN_ALLOWED_HOSTS";

    /** What the installers of 1.x added to every list: the loopback names need no option, the services of 2.x allow them always. */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private LegacyHosts() {
    }

    /**
     * Refuses the parameters when the 1.x file allows a public host name that they do not.
     *
     * @throws org.pgledger.cli.FailureException ENV_FILE_FAILED when the file cannot be read
     * @throws UsageException LEGACY_HOSTS_DROPPED naming the host names and the option; the names of the MCP server come first
     */
    static void require(Path file, DeployParameters parameters) {
        OneXEnvironment environment = OneXEnvironment.read(file);
        refuseDropped(dropped(environment, MCP_KEY, parameters.mcpHosts()), DeployOptions.MCP_HOST);
        refuseDropped(dropped(environment, ADMIN_KEY, parameters.adminHosts()), DeployOptions.ADMIN_HOST);
    }

    private static void refuseDropped(List<String> dropped, String option) {
        if (!dropped.isEmpty()) {
            throw new UsageException(ErrorCode.LEGACY_HOSTS_DROPPED, String.join(", ", dropped), option);
        }
    }

    /**
     * The public names of the setting that the given names do not carry: in lower case (host names are not case sensitive and the
     * Host check of 2.x ignores case), each once, in the order of the file. Blank entries, the loopback names and an absent or
     * empty setting give nothing. A value that is no plain host name (a port, a wildcard) is named as it stands, so that it is
     * never lost without a word.
     */
    static List<String> dropped(OneXEnvironment environment, String key, List<String> given) {
        Set<String> carried = new LinkedHashSet<>();
        given.forEach(name -> carried.add(name.toLowerCase(Locale.ROOT)));
        Set<String> dropped = new LinkedHashSet<>();
        for (String entry : environment.value(key).orElse("").split(",")) {
            String name = entry.strip().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !LOOPBACK.contains(name) && !carried.contains(name)) {
                dropped.add(name);
            }
        }
        return List.copyOf(dropped);
    }
}
