package org.pgledger.cli.seed;

import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.springframework.stereotype.Component;

/**
 * The defaults of an installation, from the seed bundle: the one place they are written ({@code src/seed/install-defaults.properties}).
 * {@code render-deploy}, {@code prepare-host} and {@code seal-host} take them when an option is not given, {@code mcp-check}
 * takes the port of the MCP server, and the installers ask for them with {@code install-default}, so no script and no class
 * holds a second copy.
 */
@Component
public class InstallDefaults {

    /** The name of an instance: its services, its service user and its directories follow it. */
    public static final String INSTANCE_NAME = "instance-name";
    /** The loopback port of the MCP server. */
    public static final String MCP_PORT = "mcp-port";
    /** The loopback port of the admin web. */
    public static final String ADMIN_PORT = "admin-port";
    /** Every default of the installation; {@code install-default} accepts these names only. */
    public static final List<String> NAMES = List.of(INSTANCE_NAME, MCP_PORT, ADMIN_PORT);

    private final PackagedSeed seed;

    public InstallDefaults(PackagedSeed seed) {
        this.seed = seed;
    }

    /**
     * @param name one of {@link #NAMES}
     * @throws FailureException SEED_UNAVAILABLE when the bundle cannot be read or does not have the default
     */
    public String value(String name) {
        String value = seed.installDefaults().get(name);
        if (value == null) {
            throw new FailureException(ErrorCode.SEED_UNAVAILABLE, "no install default named " + name);
        }
        return value;
    }

    public String instanceName() {
        return value(INSTANCE_NAME);
    }

    public int mcpPort() {
        return number(MCP_PORT);
    }

    public int adminPort() {
        return number(ADMIN_PORT);
    }

    private int number(String name) {
        try {
            return Integer.parseInt(value(name));
        } catch (NumberFormatException notANumber) {
            throw new FailureException(ErrorCode.SEED_UNAVAILABLE, notANumber, "the install default " + name + " is no number");
        }
    }
}
