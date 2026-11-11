package org.pgledger.cli.secret;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.attribute.UserPrincipal;

/** Boundary to the account this process runs as; the owner of every secret path must be that account. */
interface ProcessUser {

    UserPrincipal current(FileSystem fileSystem) throws IOException;
}
