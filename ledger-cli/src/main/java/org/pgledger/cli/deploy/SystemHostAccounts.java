package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import org.springframework.stereotype.Component;

/** The accounts of this host, by the lookup service of the file system. */
@Component
class SystemHostAccounts implements HostAccounts {

    @Override
    public UserPrincipal user(FileSystem fileSystem, String name) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @Override
    public GroupPrincipal group(FileSystem fileSystem, String name) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByGroupName(name);
    }
}
