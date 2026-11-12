package org.pgledger.cli.deploy;

import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;
import org.pgledger.cli.seed.InstallDefaults;
import org.pgledger.cli.seed.PackagedSeed;

/**
 * The installer components made by hand for a test that needs no context: the platforms, the renderer and the reader of the
 * options with a real Jakarta validator. The components take their collaborators by constructor, so this is what the container
 * does; the accounts and the process user are never asked by what these tests do (they render and validate).
 */
final class DeployTestBeans {

    private DeployTestBeans() {
    }

    static ServicePlatforms platforms() {
        DeployFiles files = new DeployFiles();
        HostFiles host = new HostFiles(files);
        HostAccounts accounts = new SystemHostAccounts();
        return new ServicePlatforms(List.of(new LinuxPlatform(accounts, host, files),
                new WindowsPlatform(accounts, fileSystem -> () -> "tester", new WindowsAccess(), host, files)));
    }

    /** The install defaults of the seed bundle that the build made (the class path of the tests has it). */
    static InstallDefaults defaults() {
        return new InstallDefaults(new PackagedSeed());
    }

    static DeployInputReader reader() {
        return new DeployInputReader(Validation.buildDefaultValidatorFactory().getValidator(), platforms(), defaults());
    }

    static DeployRenderer renderer() {
        return new DeployRenderer(platforms());
    }

    /** The validated parameters of the options (the refusals of the reader are thrown). */
    static DeployParameters parameters(Map<String, String> values) {
        return reader().parameters(values);
    }

    static List<RenderedFile> render(Map<String, String> values) {
        return renderer().render(parameters(values));
    }
}
