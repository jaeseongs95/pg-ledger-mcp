package org.pgledger.cli.deploy;

/**
 * One file of the deployment as text.
 *
 * @param path    where the file belongs, absolute and with {@code /} (for example {@code /etc/systemd/system/x.service}); the
 *                writer puts it below its root
 * @param content the whole text: line ends are {@code \n}, and it ends with one
 */
public record RenderedFile(String path, String content) {
}
