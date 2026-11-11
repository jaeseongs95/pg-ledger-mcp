package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;

/**
 * How the secret directory and its files are made usable by their owner alone on this file system. The file system decides
 * by the attribute views it supports, never the name of the operating system (rule ARC-033):
 *
 * <ul>
 * <li>{@link Posix}: the permissions {@code rwx------} (directory) and {@code rw-------} (file), given at creation; a path
 *     with any group or other permission is refused.
 * <li>{@link Acl} ({@link AclFileAttributeView}, the Windows file systems): an ACL with one ALLOW entry that grants the
 *     owner everything, and nothing inherited, given at creation (the {@code acl:acl} attribute of the Windows provider);
 *     a path whose ACL allows more than looking at its attributes and ACL to any other principal is refused. SYSTEM and
 *     Administrators are not exempt: an administrator can take ownership of any file whatever its ACL, so this protects
 *     against the other accounts of the machine, and an entry for them would add exposure only. The same rule and the same
 *     harmless set as the key store of the hook ({@code ledger-client-hook}, {@code SessionKeyStore}); the two modules do not
 *     depend on each other, so the few lines are repeated. Reference:
 *     https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html
 * </ul>
 */
sealed interface SecretProtection {

    /** The attributes a new directory is created with. */
    FileAttribute<?>[] directoryAttributes();

    /** The attributes a new file is created with. */
    FileAttribute<?>[] fileAttributes();

    /**
     * Refuses a path that another principal can use.
     *
     * @throws FailureException LOOSE_PERMISSIONS naming the path and what is open
     */
    void requireOwnerOnlyAccess(Path path) throws IOException;

    /** For a path this process has just created, before a byte of a secret is written: makes the protection certain. */
    default void settle(Path path) throws IOException {
        requireOwnerOnlyAccess(path);
    }

    /** The protection of this file system, or empty when it has neither POSIX permissions nor ACLs. */
    static Optional<SecretProtection> of(FileSystem fileSystem, ProcessUser processUser) throws IOException {
        Set<String> views = fileSystem.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return Optional.of(new Posix());
        }
        if (views.contains("acl")) {
            return Optional.of(new Acl(processUser.current(fileSystem)));
        }
        return Optional.empty();
    }

    /** POSIX permissions. */
    record Posix() implements SecretProtection {

        private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
        private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
        private static final Set<PosixFilePermission> GROUP_AND_OTHERS = PosixFilePermissions.fromString("---rwxrwx");

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)};
        }

        @Override
        public void requireOwnerOnlyAccess(Path path) throws IOException {
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), PosixFilePermissions.toString(mode));
            }
        }
    }

    /** The {@code acl:acl} attribute of the Windows file system provider: the DACL a new file or directory gets. */
    record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {

        @Override
        public String name() {
            return "acl:acl";
        }
    }

    /** An ACL with one entry for the owner. */
    record Acl(UserPrincipal owner) implements SecretProtection {

        /** What another principal may hold without reaching a secret: its attributes, its ACL, the wait for the handle. */
        private static final Set<AclEntryPermission> HARMLESS = EnumSet.of(AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);

        /** One ALLOW entry that grants the owner everything; a directory's entry is inherited by what it holds. */
        private List<AclEntry> ownerOnly(boolean directory) {
            Set<AclEntryFlag> flags = directory
                    ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    : EnumSet.noneOf(AclEntryFlag.class);
            return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build());
        }

        @Override
        public FileAttribute<?>[] directoryAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(true))};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(false))};
        }

        @Override
        public void requireOwnerOnlyAccess(Path path) throws IOException {
            List<AclEntry> acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .getAcl();
            if (acl.isEmpty()) {
                // an ACL without any entry grants the owner nothing, and a NULL DACL (which grants everybody everything)
                // may be reported the same way: neither is the "owner alone" that was asked for
                throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(), "an ACL without an entry");
            }
            for (AclEntry entry : acl) {
                // only an ALLOW entry grants anything; DENY, AUDIT and ALARM entries do not
                boolean granting = entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                        && !HARMLESS.containsAll(entry.permissions());
                if (granting) {
                    throw new FailureException(ErrorCode.LOOSE_PERMISSIONS, path.toString(),
                            "an ACL that allows " + entry.principal().getName());
                }
            }
        }

        /**
         * The ACL given at creation may have been merged with the entries the parent hands down: when it was, it is replaced
         * by the owner-only ACL before a secret is written. This is the one place where a protection is changed after
         * creation, and only ever to the narrow one.
         */
        @Override
        public void settle(Path path) throws IOException {
            try {
                requireOwnerOnlyAccess(path);
            } catch (FailureException wider) {
                if (wider.code() != ErrorCode.LOOSE_PERMISSIONS) {
                    throw wider;
                }
                Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(ownerOnly(Files.isDirectory(path)));
                requireOwnerOnlyAccess(path);
            }
        }
    }
}
