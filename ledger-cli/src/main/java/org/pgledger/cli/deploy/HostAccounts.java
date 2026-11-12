package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;

/**
 * Boundary to the accounts of the host that an installation names: the service user and its group (Linux), the well-known
 * accounts SYSTEM, Administrators and LocalService (Windows). The product resolves a name with the lookup service of the
 * file system ({@link java.nio.file.attribute.UserPrincipalLookupService}); a test plays the accounts, because only an
 * administrator may give a file to another account.
 */
public interface HostAccounts {

    /** @throws java.nio.file.attribute.UserPrincipalNotFoundException when the host has no such account */
    UserPrincipal user(FileSystem fileSystem, String name) throws IOException;

    /** @throws java.nio.file.attribute.UserPrincipalNotFoundException when the host has no such group */
    GroupPrincipal group(FileSystem fileSystem, String name) throws IOException;
}
