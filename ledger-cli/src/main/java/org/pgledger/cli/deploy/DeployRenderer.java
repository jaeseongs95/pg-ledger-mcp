package org.pgledger.cli.deploy;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the files of the deployment from validated parameters: the settings files ({@link ServiceSettings}, every
 * platform) and the definition of the two services that the platform runs them with ({@link SystemdUnits} on Linux,
 * {@link WinswServices} on Windows), and on Linux, when asked, the nginx site ({@link NginxSite}). It is a pure function of
 * its input (no clock, no host name, no environment, no file access), so the output is the same text every time and a test
 * needs neither root, nor Linux, nor Windows. A target that needs another definition is one more class next to those and one
 * more branch here.
 */
public final class DeployRenderer {

    private DeployRenderer() {
    }

    /** The files in a fixed order: the service definitions, the settings files, then the nginx site when there is one. */
    public static List<RenderedFile> render(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        List<RenderedFile> files = new ArrayList<>();
        switch (layout.platform()) {
            case LINUX -> {
                files.add(new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + mcp.name() + ".service",
                        SystemdUnits.unit(p, mcp, "network-online.target", null)));
                files.add(new RenderedFile(DeployLayout.UNIT_DIRECTORY + "/" + admin.name() + ".service",
                        SystemdUnits.unit(p, admin, "network-online.target " + mcp.name() + ".service", layout.etc())));
            }
            case WINDOWS -> {
                files.add(new RenderedFile(layout.lib() + "/" + mcp.name() + ".xml", WinswServices.configuration(p, mcp)));
                files.add(new RenderedFile(layout.lib() + "/" + admin.name() + ".xml",
                        WinswServices.configuration(p, admin)));
            }
        }
        files.add(new RenderedFile(layout.etc() + "/application.yaml", ServiceSettings.shared(p)));
        files.add(new RenderedFile(layout.etc() + "/" + mcp.settingsFile(), ServiceSettings.mcp(p)));
        files.add(new RenderedFile(layout.etc() + "/" + admin.settingsFile(), ServiceSettings.admin(p)));
        if (p.nginx() != null) {
            files.add(new RenderedFile(p.nginx().conf(), NginxSite.render(p)));
        }
        return List.copyOf(files);
    }

    /**
     * The sources of {@code spring.config.import} of a service, below the one directory of the installation: the settings
     * file the installer renders (required), the settings file of the service (required), the operator's own settings
     * ({@code local.yaml}, optional, never overwritten), the registration of the JDBC driver and the secret directory (a
     * configuration tree). Later sources win. The command line tool imports the same four of them without the file of a
     * service (its {@code application.properties}), so it sees what the services see.
     *
     * @param settingsFile {@code mcp.yaml} or {@code admin.yaml}
     */
    public static String configImport(DeployLayout layout, String settingsFile) {
        String etc = layout.etc();
        return "file:" + etc + "/application.yaml,file:" + etc + "/" + settingsFile + ",optional:file:" + etc
                + "/local.yaml,optional:file:" + etc + "/jdbc-driver.yaml,optional:configtree:" + etc + "/secrets/";
    }
}
