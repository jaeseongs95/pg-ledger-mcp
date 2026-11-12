package org.pgledger.cli.deploy;

import java.nio.file.Path;

/**
 * What the options every deployment command shares ({@link DeployOptions#ALL}) come to once {@link DeployInputReader} has
 * read and validated them as one.
 *
 * @param parameters the validated parameters
 * @param root       the directory to put everything below, or null to use the real paths
 */
record DeployInput(DeployParameters parameters, Path root) {
}
