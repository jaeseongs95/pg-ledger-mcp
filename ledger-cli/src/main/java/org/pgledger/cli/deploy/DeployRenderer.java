package org.pgledger.cli.deploy;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the files of the deployment from validated parameters: the systemd unit ({@link SystemdUnits}) and the settings
 * file ({@link ServiceSettings}) of each of the two services and, when asked, the nginx site ({@link NginxSite}). It is a
 * pure function of its input (no clock, no host name, no environment, no file access), so the output is the same text every
 * time and a test needs neither root nor Linux. A definition of the services for another operating system is one more
 * class next to {@link SystemdUnits} and one more call here.
 */
public final class DeployRenderer {

    private DeployRenderer() {
    }

    /** The files in a fixed order: the two units, the two settings files, then the nginx site when there is one. */
    public static List<RenderedFile> render(DeployParameters p) {
        List<RenderedFile> files = new ArrayList<>();
        files.add(new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + DeployLayout.MCP_NAME + ".service",
                SystemdUnits.unit(p, DeployLayout.MCP_NAME, "the ledger MCP server", DeployLayout.MCP_JAR,
                        "network-online.target", null)));
        files.add(new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + DeployLayout.ADMIN_NAME + ".service",
                SystemdUnits.unit(p, DeployLayout.ADMIN_NAME, "the ledger admin web", DeployLayout.ADMIN_JAR,
                        "network-online.target " + DeployLayout.MCP_NAME + ".service",
                        DeployLayout.etc(DeployLayout.ADMIN_NAME))));
        files.add(new RenderedFile(DeployLayout.etc(DeployLayout.MCP_NAME) + "/application.yaml", ServiceSettings.mcp(p)));
        files.add(new RenderedFile(DeployLayout.etc(DeployLayout.ADMIN_NAME) + "/application.yaml",
                ServiceSettings.admin(p)));
        if (p.nginx() != null) {
            files.add(new RenderedFile(p.nginx().conf(), NginxSite.render(p)));
        }
        return List.copyOf(files);
    }

    /**
     * The sources of {@code spring.config.import} of a service, below its own directory only: the settings file this
     * installer renders (required), the operator's own settings ({@code local.yaml}, optional, never overwritten), the
     * registration of the JDBC driver and the secret directory (a configuration tree). Later sources win. The
     * command line tool imports the same four for {@code --ledger.secrets.instance=<service>} (its
     * {@code application.properties}), so it sees what the service sees.
     */
    public static String configImport(String service) {
        String etc = DeployLayout.etc(service);
        return "file:" + etc + "/application.yaml,optional:file:" + etc + "/local.yaml,optional:file:" + etc
                + "/jdbc-driver.yaml,optional:configtree:" + etc + "/secrets/";
    }
}
