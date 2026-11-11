package org.pgledger.cli.deploy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * Writes rendered files below a root. Each file is written the way the secret store writes a secret: a temporary file in the
 * directory of the target, then an atomic move over the target, so a reader (systemd, nginx, the service) never sees half a
 * file and a failed write leaves the previous file as it was. The mode {@code rw-r--r--} is given when the temporary file
 * is created, never changed afterwards: the files hold no secret and the service users have to read them.
 */
public final class DeployFiles {

    private static final FileAttribute<?> READABLE = PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"));

    private DeployFiles() {
    }

    /**
     * @param root the directory the absolute paths of the files are put below ({@code /} on a host)
     * @return the files written, in order
     * @throws FailureException DEPLOY_WRITE_FAILED naming the file and the kind of failure
     */
    public static List<Path> write(Path root, List<RenderedFile> files) {
        List<Path> written = new ArrayList<>();
        for (RenderedFile file : files) {
            Path target = root.resolve(file.path().substring(1));
            try {
                replace(target, file.content());
            } catch (IOException e) {
                throw new FailureException(ErrorCode.DEPLOY_WRITE_FAILED, e, target.toString(),
                        e.getClass().getSimpleName());
            }
            written.add(target);
        }
        return written;
    }

    private static void replace(Path target, String content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile(directory, "..render-", ".tmp", READABLE)
                : Files.createTempFile(directory, "..render-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
