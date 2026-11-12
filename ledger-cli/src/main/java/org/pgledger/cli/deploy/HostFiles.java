package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.List;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.springframework.stereotype.Component;

/**
 * What the host platforms ({@link ServicePlatform}) do to the files of the host in common: make a directory with its owner and
 * mode, copy the jars of the product, check what the file system supports, and report a failure of the host. A component,
 * because it changes the file system; the report of a failure ({@link #failure}) is a pure function.
 */
@Component
final class HostFiles {

    private final DeployFiles files;

    HostFiles(DeployFiles files) {
        this.files = files;
    }

    /** Creates the directory when it is not there and gives it the owner and the mode, as {@code install -d -m -o -g} did. */
    void directory(Path path, String mode, UserPrincipal owner, GroupPrincipal group) throws IOException {
        Files.createDirectories(path);
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (owner != null) {
            view.setOwner(owner);
            view.setGroup(group);
        }
        view.setPermissions(PosixFilePermissions.fromString(mode));
    }

    /** The three jars of the product to {@code lib} and the JDBC driver jar to {@code drivers}. */
    void copyProduct(Path jars, Path driver, Path lib, Path drivers) throws IOException {
        for (String jar : List.of(DeployLayout.CLI_JAR, DeployLayout.MCP_JAR, DeployLayout.ADMIN_JAR)) {
            files.copy(jars.resolve(jar), lib.resolve(jar));
        }
        files.copy(driver, drivers.resolve(driver.getFileName().toString()));
    }

    /**
     * The file system decides by the attribute views it supports, never by the name of the operating system.
     *
     * @throws FailureException HOST_UNSUPPORTED naming the view
     */
    void requireView(FileSystem fileSystem, String view) {
        if (!fileSystem.supportedFileAttributeViews().contains(view)) {
            throw new FailureException(ErrorCode.HOST_UNSUPPORTED, view);
        }
    }

    /**
     * The path (or the account) and the kind of the failure; never the text of the exception. A backup of 1.x that holds other
     * bytes than its file names both files.
     */
    static FailureException failure(IOException e) {
        String subject = e instanceof DeployFiles.BackupDiffersException differs ? differs.files()
                : e instanceof FileSystemException failed && failed.getFile() != null ? failed.getFile()
                : e instanceof UserPrincipalNotFoundException missing ? missing.getName() : "-";
        return new FailureException(ErrorCode.HOST_FAILED, e, subject, e.getClass().getSimpleName());
    }
}
