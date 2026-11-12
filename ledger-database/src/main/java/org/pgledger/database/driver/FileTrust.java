package org.pgledger.database.driver;

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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may write what the driver loader is about to run: a driver jar and the directory it is in. The code of the jar
 * runs inside the process, so whoever can replace the jar, or the directory entry that names it, controls the product.
 * A file or directory is trusted when its owner is the process user or an administrative account of the machine, and
 * nobody else can modify it. The file system decides how that is read, by the attribute views it supports and not by
 * the name of the operating system:
 *
 * <ul>
 * <li>POSIX permissions ({@link PosixFileAttributeView}): the group and others must not have the write bit.
 *     The administrative account is {@code root}.</li>
 * <li>ACL ({@link AclFileAttributeView}, the Windows file systems): every ALLOW entry that applies to the object itself
 *     (an entry that is only inherited by what the object contains does not) and holds a right to change data, delete,
 *     or change the ACL or the owner ({@link #MODIFYING}) must be for the process user, the owner, {@code SYSTEM} or
 *     {@code Administrators}. DENY entries are not counted: they can only take away. An ACL without any entry is
 *     refused: a NULL DACL grants full access to any user and an empty DACL grants no access
 *     (https://learn.microsoft.com/en-us/windows/win32/secauthz/null-dacls-and-empty-dacls), and
 *     {@link AclFileAttributeView#getAcl} does not say which of them an empty list is
 *     (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html),
 *     and neither is a protection that can be told from the outside. The accounts are found by name with the file
 *     system's {@code UserPrincipalLookupService}; an account that does not exist on the machine (a name that this
 *     system does not have) is simply not trusted, so a system whose language names these accounts differently than
 *     {@code SYSTEM} and {@code Administrators} trusts only the process user and the owner of the file.</li>
 * <li>Neither view: nothing can be established, so nothing is trusted.</li>
 * </ul>
 *
 * <p>The checks never follow a symbolic link ({@link LinkOption#NOFOLLOW_LINKS}, https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/LinkOption.html;
 * {@link Files#getFileAttributeView} returns {@code null} for a view the file system does not have, which is how the
 * view is chosen). The caller checks the file it has already opened (see
 * {@link DriverLoader}): the JDK gives no attributes of an open channel, so they are read by path right after the open.
 */
final class FileTrust {

    private static final Logger LOG = LoggerFactory.getLogger(FileTrust.class);

    /** The administrative accounts of the file systems this product runs on, looked up by name where they exist. */
    private static final List<String> ADMINISTRATIVE_ACCOUNTS = List.of("root", "SYSTEM", "Administrators");

    /** What changes a file or a directory (a directory's names for adding and removing entries included). */
    static final Set<AclEntryPermission> MODIFYING = EnumSet.of(AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA, AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);

    private static final Set<PosixFilePermission> GROUP_OR_OTHERS_WRITE =
            EnumSet.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

    private final List<UserPrincipal> trusted;

    /**
     * @param processUser the account the process runs as (the owner of a file it has just created)
     */
    FileTrust(FileSystem fileSystem, UserPrincipal processUser) {
        List<UserPrincipal> accounts = new ArrayList<>();
        accounts.add(processUser);
        for (String name : ADMINISTRATIVE_ACCOUNTS) {
            try {
                accounts.add(fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name));
            } catch (IOException absent) {
                LOG.debug("the account {} is not known here; it is not trusted as an owner or a writer", name, absent);
            }
        }
        this.trusted = List.copyOf(accounts);
    }

    /**
     * Checks one file or directory.
     *
     * @param what what the path is, for the message ("the jar", "the directory of the jar")
     * @throws InfrastructureException DRIVER_INVALID naming the path and the reason (the path is the one given)
     */
    void require(Path path, String what) {
        try {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            if (!isTrusted(owner)) {
                throw invalid(what + " is owned by " + owner.getName() + ", who is neither the process user nor an "
                        + "administrative account: " + path);
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                requireNotWritableByGroupOrOthers(path, what, posix);
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (acl != null) {
                requireOnlyTrustedWriters(path, what, owner, acl);
                return;
            }
            throw invalid(what + " cannot be checked: its file system has neither POSIX permissions nor ACLs: " + path);
        } catch (IOException e) {
            throw new InfrastructureException(InfrastructureCode.DRIVER_INVALID,
                    List.of("cannot read the owner and the permissions of " + what + " " + path), e);
        }
    }

    private boolean isTrusted(UserPrincipal principal) {
        return trusted.stream().anyMatch(principal::equals);
    }

    private static void requireNotWritableByGroupOrOthers(Path path, String what, PosixFileAttributeView view)
            throws IOException {
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        if (!Collections.disjoint(permissions, GROUP_OR_OTHERS_WRITE)) {
            throw invalid(what + " is writable by its group or by others: " + path);
        }
    }

    private void requireOnlyTrustedWriters(Path path, String what, UserPrincipal owner, AclFileAttributeView view)
            throws IOException {
        List<AclEntry> entries = view.getAcl();
        if (entries.isEmpty()) {
            throw invalid(what + " has an ACL without any entry (an empty ACL, or a NULL DACL: nobody or everybody may use it): "
                    + path);
        }
        for (AclEntry entry : entries) {
            boolean appliesHere = entry.type() == AclEntryType.ALLOW
                    && !entry.flags().contains(AclEntryFlag.INHERIT_ONLY);
            boolean modifies = !Collections.disjoint(entry.permissions(), MODIFYING);
            if (appliesHere && modifies && !Objects.equals(owner, entry.principal()) && !isTrusted(entry.principal())) {
                throw invalid(what + " can be changed by " + entry.principal().getName() + ", who is neither the process user, "
                        + "the owner nor an administrative account: " + path);
            }
        }
    }

    private static InfrastructureException invalid(String reason) {
        return new InfrastructureException(InfrastructureCode.DRIVER_INVALID, List.of(reason));
    }
}
