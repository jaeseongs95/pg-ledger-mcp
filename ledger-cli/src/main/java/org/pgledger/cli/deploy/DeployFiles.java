package org.pgledger.cli.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.springframework.stereotype.Component;

/**
 * Writes rendered files below a root. Each file is written the way the secret store writes a secret: a temporary file in the
 * directory of the target, then an atomic move over the target, so a reader (systemd, nginx, the service) never sees half a
 * file and a failed write leaves the previous file as it was. The mode {@code rw-r--r--} is given when the temporary file
 * is created, never changed afterwards: the files hold no secret and the service users have to read them. The jars and the
 * driver of an installation are copied the same way ({@link #copy}). It is a component because it changes the file system.
 *
 * <p>Without a root a file is written at its own path, and that path has to be a path of this host: a Windows drive path on a
 * host that has no drives, or a path of the root of a Linux file system on a host that has none, is no path of the host but a
 * relative one that would be written below the working directory. {@link #resolve} refuses it (naming the option
 * {@code platform}), and {@link #requireReachable} lets a command do that before it reads or changes anything.
 */
@Component
public final class DeployFiles {

    private static final FileAttribute<?> READABLE = PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"));

    /** What a file of 1.x that is replaced is kept as. */
    public static final String BACKUP_SUFFIX = ".1x-backup";

    /**
     * @param root the directory the absolute paths of the files are put below (a drive letter becomes a directory name:
     *             {@code C:/x} is {@code <root>/C/x}), or null to write each file at its own path, as an installation does
     * @param kept told the backup file of every foreign file that was moved aside ({@link #BACKUP_SUFFIX})
     * @return the files written, in order
     * @throws UsageException INVALID_DEPLOY_VALUE naming {@code platform}, before the first file is written, when there is no
     *         root and a file has no path on this host
     * @throws FailureException DEPLOY_WRITE_FAILED naming the file and the kind of failure
     */
    public List<Path> write(Path root, List<RenderedFile> files, Consumer<Path> kept) {
        List<Path> targets = new ArrayList<>();
        for (RenderedFile file : files) {
            targets.add(resolve(root, file.path()));
        }
        List<Path> written = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            Path target = targets.get(index);
            String content = files.get(index).content();
            try {
                keepForeign(target, kept);
                replace(target, out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new FailureException(ErrorCode.DEPLOY_WRITE_FAILED, e, target.toString(),
                        e.getClass().getSimpleName());
            }
            written.add(target);
        }
        return written;
    }

    /**
     * A file of that name that this command did not render (the unit of a 1.x installation, a hand-written one) is moved to
     * {@code <file>.1x-backup} before it is replaced, so the old installation can be put back (the rollback of the migration).
     * The move is a rename without {@code REPLACE_EXISTING}: an existing backup is never overwritten, the move fails and the
     * write is refused with nothing of either file changed. A file this command rendered (it says so, {@link Text#MARKER}) is
     * replaced as it is: every run after the first finds its own file.
     */
    private void keepForeign(Path target, Consumer<Path> kept) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                // the marker is ASCII: a one-byte decoding finds it in any text and never fails on a file of another encoding
                || Files.readString(target, StandardCharsets.ISO_8859_1).contains(Text.MARKER)) {
            return;
        }
        Path backup = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
        // no REPLACE_EXISTING and no ATOMIC_MOVE (which may replace silently): a backup that exists makes the move fail
        Files.move(target, backup);
        kept.accept(backup);
    }

    /**
     * Where a path of a file (absolute, {@code /} also on Windows) is put: itself, or below {@code root} (a drive letter is a
     * directory name).
     *
     * @throws UsageException INVALID_DEPLOY_VALUE naming {@code platform} when there is no root and the path is not an
     *         absolute path of this host (it is the path of the other platform)
     */
    public Path resolve(Path root, String path) {
        if (root != null) {
            return root.resolve(path.replaceFirst("^([A-Za-z]):", "$1").replaceFirst("^/", ""));
        }
        try {
            Path own = Path.of(path);
            if (own.isAbsolute()) {
                return own;
            }
        } catch (InvalidPathException e) {
            throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, e, DeployOptions.PLATFORM);
        }
        throw new UsageException(ErrorCode.INVALID_DEPLOY_VALUE, DeployOptions.PLATFORM);
    }

    /** Refuses a layout whose folders are no paths of this host (see {@link #resolve}); nothing is read or written. */
    public void requireReachable(Path root, DeployLayout layout) {
        resolve(root, layout.lib());
        resolve(root, layout.etc());
        if (layout.logs() != null) {
            resolve(root, layout.logs());
        }
    }

    /**
     * Copies a file the way a rendered file is written: a temporary file in the directory of the target, mode
     * {@code rw-r--r--} (where the file system has modes), then an atomic move over the target. A jar that a running process
     * has open (Linux) keeps its old content for that process.
     */
    public void copy(Path source, Path target) throws IOException {
        replace(target, out -> {
            try (InputStream in = Files.newInputStream(source)) {
                in.transferTo(out);
            }
        });
    }

    /** What writes the content of a file. */
    @FunctionalInterface
    private interface Content {

        void writeTo(OutputStream out) throws IOException;
    }

    private void replace(Path target, Content content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile(directory, "..render-", ".tmp", READABLE)
                : Files.createTempFile(directory, "..render-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                content.writeTo(out);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
