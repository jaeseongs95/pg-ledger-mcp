package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.deploy.DeployLayout.Platform;
import org.springframework.boot.ApplicationArguments;

/**
 * What differs between the operating system families an installation is made for: how its two services are defined, what
 * the layout of the instance is, and what is done to the files of the host before the secrets exist and after the services
 * are registered. One implementation per {@link Platform}, each a component, found by the enum in one place
 * ({@link ServicePlatforms}); a platform without its component stops the start of the tool. The commands and the renderer
 * ask the platform and branch on none.
 *
 * <p>The family is closed (Linux and Windows; macOS is no target), but it is the one axis along which the installer varies,
 * and every place that used to branch on it (the renderer, {@code prepare-host}, {@code seal-host}, the validation of the
 * options) is now a method here.
 */
interface ServicePlatform {

    Platform platform();

    /**
     * The layout of the instance on this platform.
     *
     * @param home the {@code --home} option, or null when it was not given
     * @throws org.pgledger.cli.UsageException MISSING_ARGUMENT when the platform needs a home and none was given,
     *         INVALID_DEPLOY_VALUE naming {@code home} when it is not a home of this platform
     */
    DeployLayout layout(String name, String home);

    /** The files that define the two services (the MCP server and the admin web) for the service manager of the platform. */
    List<RenderedFile> serviceDefinitions(DeployParameters parameters);

    /**
     * The files {@code prepare-host} copies besides the jars and the driver (the executable of the service wrapper), read from
     * its options.
     *
     * @throws org.pgledger.cli.UsageException for an option this platform has no use for, or a required one that is absent
     */
    List<Path> wrappers(ApplicationArguments arguments);

    /**
     * Prepares the files of the host ({@code prepare-host}): the folders with their owner and mode, or their ACLs, and the
     * copies of the jars, the driver and the wrappers. Everything is looked up that can refuse before the first change.
     *
     * @param root the directory to put everything below, or null for the real paths
     * @param wrappers what {@link #wrappers} found
     */
    void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException;

    /**
     * The directories of the automatic certificate ({@code --tls-acme}): the TLS directory (the keys; the service account's
     * alone) and the web root of the HTTP-01 proof (the service account writes it, the web server reads it). Only a platform
     * that has the nginx site has them.
     *
     * @throws org.pgledger.cli.UsageException INVALID_DEPLOY_VALUE naming {@code tls-acme} on a platform without the site
     */
    default void prepareTls(DeployLayout layout, Path root) throws IOException {
        throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.TLS_ACME);
    }

    /**
     * The last step of an installation ({@code seal-host}).
     *
     * @throws org.pgledger.cli.UsageException INVALID_DEPLOY_VALUE naming {@code platform} when there is nothing to seal
     */
    void seal(DeployLayout layout, Path root) throws IOException;
}
