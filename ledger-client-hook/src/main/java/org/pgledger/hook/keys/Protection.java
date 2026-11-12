package org.pgledger.hook.keys;

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
import java.util.Objects;
import java.util.Set;
import org.pgledger.hook.keys.SessionKeyException.Reason;

/**
 * How a key folder and a key file are protected on a file system, and how that is checked. Chosen by the capability of
 * the file system and never by the operating system name:
 * <ul>
 * <li>POSIX attribute view ({@link Posix}): a folder gets {@code rwx------} and a file {@code rw-------}, both given at
 *     creation and never widened; an existing folder or file with any group or other permission bit is refused
 *     ({@link Reason#TOO_OPEN}).
 * <li>ACL attribute view ({@link Acl}; {@link AclFileAttributeView}, the Windows file systems;
 *     https://docs.oracle.com/en/java/javase/25/docs/api/java/nio/file/attribute/AclFileAttributeView.html): a folder
 *     or file is given, at creation (the {@code acl:acl} attribute of
 *     {@link Files#createDirectories(Path, FileAttribute[])} and {@link Files#newByteChannel(Path, java.util.Set,
 *     FileAttribute[])}), an ACL with one ALLOW entry that grants the current user everything and nothing else; the ACL
 *     is read back before any key material is written, and replaced by that ACL if the system added inherited entries.
 *     An existing folder or file whose ACL has no entry at all (an empty ACL; a NULL DACL may read the same) or allows
 *     anything beyond reading its attributes and its ACL to a principal other than the current user is refused
 *     ({@link Reason#TOO_OPEN}). SYSTEM and Administrators are not exempt: an administrator can take ownership of any
 *     file whatever its ACL, so this protects against the other accounts of the machine that are not administrators, and
 *     an entry for them would add nothing but exposure.
 * <li>Both: the owner of the folder and of the file must be the current user ({@link Reason#WRONG_OWNER}); the current
 *     user is the account name that {@code Main} reads from {@code user.name}, resolved by the file system's
 *     {@code UserPrincipalLookupService}. The group Administrators is not accepted as owner: Microsoft documents that
 *     for objects such as files created by members of Domain Admins "the default owner is the Administrators group"
 *     (https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/understand-security-groups, "Domain
 *     Admins"). That page says nothing about local administrators or an elevated token, and the policy page of
 *     "System objects: Default owner for objects created by members of the Administrators group" could not be opened
 *     (the guessed Learn URLs answered 404), so no claim beyond that sentence is made. The consequence is on the safe
 *     side: a hook that an administrator runs with a token whose files are owned by that group is refused
 *     ({@link Reason#WRONG_OWNER}); the host starts hooks as the signed-in user, so run it that way.
 * <li>Neither view ({@link Unprotected}): nothing is set, the default protection of the user profile applies.
 * </ul>
 */
sealed interface Protection permits Protection.Posix, Protection.Acl, Protection.Unprotected {

    /** The attributes that a folder is created with. */
    FileAttribute<?>[] folderAttributes();

    /** The attributes that a key file is created with. */
    FileAttribute<?>[] fileAttributes();

    /** Refuses a path that another principal can use or that does not belong to the current user; changes nothing. */
    void requireOwnerOnly(Path path, LinkOption... options) throws IOException;

    /** For a path this class has just created: the same check, after the protection was made certain. */
    default void settle(Path path) throws IOException {
        requireOwnerOnly(path);
    }

    /**
     * The protection of the file system for the account {@code userName}.
     *
     * @throws SessionKeyException with {@link Reason#OWNER_UNKNOWN} where the file system has owners and no account name was given
     * @throws IOException         if the account is not known to the file system
     */
    static Protection of(FileSystem system, String userName) throws IOException {
        Set<String> views = system.supportedFileAttributeViews();
        boolean posix = views.contains("posix");
        if (!posix && !views.contains("acl")) {
            return new Unprotected();
        }
        if (userName == null || userName.isBlank()) {
            throw new SessionKeyException(Reason.OWNER_UNKNOWN);
        }
        UserPrincipal current = system.getUserPrincipalLookupService().lookupPrincipalByName(userName);
        if (posix) {
            return new Posix(current);
        }
        return new Acl(current);
    }

    /** Refuses a path whose owner is not the current user (the group Administrators is not accepted, see above). */
    private static void requireOwner(Path path, UserPrincipal current, LinkOption... options) throws IOException {
        if (!Objects.equals(Files.getOwner(path, options), current)) {
            throw new SessionKeyException(Reason.WRONG_OWNER);
        }
    }

    /** Neither view: nothing to set and nothing to check. */
    record Unprotected() implements Protection {

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return new FileAttribute<?>[0];
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[0];
        }

        @Override
        public void requireOwnerOnly(Path path, LinkOption... options) {
            // no POSIX and no ACL view: the default protection of the user profile applies
        }
    }

    /** The POSIX attribute view: modes. */
    record Posix(UserPrincipal current) implements Protection {

        private static final Set<PosixFilePermission> DIRECTORY_MODE =
                Set.copyOf(PosixFilePermissions.fromString("rwx------"));
        private static final Set<PosixFilePermission> FILE_MODE = Set.copyOf(PosixFilePermissions.fromString("rw-------"));
        private static final Set<PosixFilePermission> GROUP_AND_OTHERS =
                Set.copyOf(PosixFilePermissions.fromString("---rwxrwx"));

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)};
        }

        @Override
        public void requireOwnerOnly(Path path, LinkOption... options) throws IOException {
            requireOwner(path, current, options);
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, options);
            if (!Collections.disjoint(mode, GROUP_AND_OTHERS)) {
                throw new SessionKeyException(Reason.TOO_OPEN);
            }
        }
    }

    /** The {@code acl:acl} attribute of the Windows file system provider: the DACL a new file or folder gets. */
    record AclAttribute(List<AclEntry> value) implements FileAttribute<List<AclEntry>> {

        private static final String ACL_ATTRIBUTE = "acl:acl";

        @Override
        public String name() {
            return ACL_ATTRIBUTE;
        }
    }

    /** The ACL attribute view: entries. */
    record Acl(UserPrincipal current) implements Protection {

        /** What another principal may hold without reaching the key: looking at the attributes and the ACL, waiting for the handle. */
        private static final Set<AclEntryPermission> HARMLESS = Set.copyOf(EnumSet.of(AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE));

        /** One ALLOW entry that grants the current user everything; a folder's entry is inherited by what it holds. */
        private List<AclEntry> ownerOnly(boolean folder) {
            Set<AclEntryFlag> flags = folder
                    ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    : EnumSet.noneOf(AclEntryFlag.class);
            return List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build());
        }

        @Override
        public FileAttribute<?>[] folderAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(true))};
        }

        @Override
        public FileAttribute<?>[] fileAttributes() {
            return new FileAttribute<?>[] {new AclAttribute(ownerOnly(false))};
        }

        @Override
        public void requireOwnerOnly(Path path, LinkOption... options) throws IOException {
            requireOwner(path, current, options);
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, options);
            List<AclEntry> acl = view.getAcl();
            if (acl.isEmpty()) {
                // an ACL without any entry grants the current user nothing, and a NULL DACL (which grants everybody
                // everything) may be reported the same way: neither is the "owner alone" that was asked for
                throw new SessionKeyException(Reason.TOO_OPEN);
            }
            for (AclEntry entry : acl) {
                // only an ALLOW entry grants anything; DENY, AUDIT and ALARM entries do not
                boolean granting = entry.type() == AclEntryType.ALLOW && !Objects.equals(entry.principal(), current)
                        && !HARMLESS.containsAll(entry.permissions());
                if (granting) {
                    throw new SessionKeyException(Reason.TOO_OPEN);
                }
            }
        }

        /** The ACL given at creation may have been merged with entries inherited from the parent: make it certain. */
        @Override
        public void settle(Path path) throws IOException {
            try {
                requireOwnerOnly(path);
            } catch (SessionKeyException tooOpen) {
                if (tooOpen.reason() != Reason.TOO_OPEN) {
                    throw tooOpen;
                }
                boolean folder = Files.isDirectory(path);
                Files.getFileAttributeView(path, AclFileAttributeView.class).setAcl(ownerOnly(folder));
                requireOwnerOnly(path);
            }
        }
    }
}
