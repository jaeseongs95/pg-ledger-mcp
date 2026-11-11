package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;

import org.springframework.stereotype.Component;

/** The JVM user name resolved to a principal of the given file system, so it compares equal to file owners. */
@Component
class SystemProcessUser implements ProcessUser {

    @Override
    public UserPrincipal current(FileSystem fileSystem) throws IOException {
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }
}
