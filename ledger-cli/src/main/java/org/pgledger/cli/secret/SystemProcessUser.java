package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;

import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.springframework.stereotype.Component;

/** The account of this process (JDK ProcessHandle) resolved to a principal of the given file system, so it compares equal to file owners. */
@Component
class SystemProcessUser implements ProcessUser {

    @Override
    public UserPrincipal current(FileSystem fileSystem) throws IOException {
        String name = ProcessHandle.current().info().user()
                .orElseThrow(() -> new FailureException(ErrorCode.UNKNOWN_PROCESS_USER));
        return fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(name);
    }
}
