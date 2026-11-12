package org.pgledger.cli.deploy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders the files of the deployment from validated parameters: the definition of the two services that the platform runs
 * them with ({@link ServicePlatform#serviceDefinitions}: {@link SystemdUnits} on Linux, {@link WinswServices} on Windows),
 * the settings files ({@link ServiceSettings}, every platform), and on Linux, when asked, the nginx site
 * ({@link NginxSite}). It is a pure function of its input (no clock, no host name, no environment, no file access), so the
 * output is the same text every time and a test needs neither root, nor Linux, nor Windows. A target that needs another
 * definition is one more {@link ServicePlatform}; this class does not know the platforms.
 */
@Component
public final class DeployRenderer {

    private final ServicePlatforms platforms;

    DeployRenderer(ServicePlatforms platforms) {
        this.platforms = platforms;
    }

    /** The files in a fixed order: the service definitions, the settings files, then the nginx site when there is one. */
    public List<RenderedFile> render(DeployParameters p) {
        DeployLayout layout = p.layout();
        Service mcp = Service.mcp(layout);
        Service admin = Service.admin(layout);
        List<RenderedFile> files = new ArrayList<>(platforms.of(layout.platform()).serviceDefinitions(p));
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
