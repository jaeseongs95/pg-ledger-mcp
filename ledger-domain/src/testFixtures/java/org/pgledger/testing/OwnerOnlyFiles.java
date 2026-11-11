package org.pgledger.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Files and directories that only their owner may use, for the tests that hand a driver jar to the product. The product
 * refuses a jar, or the directory of a jar, that anybody but its owner and the administrative accounts can write. The
 * default of a temporary directory is not that on every file system: on Windows a new file inherits the entries of its
 * parent, and the parent of a test directory on a data volume (or the profile's temporary folder) has entries for other
 * accounts, so the tests set the protection they need themselves. The file system decides how: POSIX permissions
 * ({@code rwx------} for a directory, {@code rw-------} for a file) or an ACL with one entry that grants the owner
 * everything (the inheritable one for a directory), by the attribute views the file system has and not by the name of
 * the operating system.
 */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    /** Makes the file or directory usable by its owner alone; returns the same path. */
    public static Path restrict(Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
                return path;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl != null) {
                Set<AclEntryFlag> flags = directory
                        ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                        : EnumSet.noneOf(AclEntryFlag.class);
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build()));
            }
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot restrict " + path, e);
        }
    }

    /** Copies the file to the target (which must not exist) and makes the copy usable by its owner alone. */
    public static Path copy(Path source, Path target) {
        try {
            Files.copy(source, target);
            return restrict(target);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy " + source + " to " + target, e);
        }
    }

    /**
     * Copies the file into a new directory of its own (made in the temporary-file directory, usable by its owner alone),
     * under the same name; both are removed when the JVM ends. For a jar that every test of a class shares.
     */
    public static Path copyToNewDirectory(Path source) {
        try {
            Path directory = Files.createTempDirectory("pg-ledger-owner-only-");
            restrict(directory);
            directory.toFile().deleteOnExit();
            Path copy = Files.copy(source, directory.resolve(source.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
            copy.toFile().deleteOnExit();
            return restrict(copy);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy " + source + " into a new directory", e);
        }
    }

    /** Writes the file and makes it usable by its owner alone. */
    public static Path write(Path file, byte[] content) {
        try {
            return restrict(Files.write(file, content));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
