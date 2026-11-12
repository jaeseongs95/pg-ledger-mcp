package org.pgledger.cli.tls;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * What {@code tls-issue} and {@code tls-renew} are asked for, read and validated once ({@link TlsRequestReader}).
 *
 * @param hosts            the host names the certificate is for: the MCP host names and then the admin host names (when it has any), each in the order given
 * @param webroot          the web root the token files are written below
 * @param directory        the TLS directory: the account key and the two certificate files
 * @param acmeDirectory    the directory URL of the certificate authority
 * @param email            the contact address of a new account, or null
 * @param agreeToTerms     whether the operator agreed to the terms of service of the authority (only {@code tls-issue} reads it)
 * @param renewBeforeDays  a certificate that expires within this many days is renewed (only {@code tls-renew} reads it)
 */
record TlsRequest(List<String> hosts, Path webroot, Path directory, URI acmeDirectory, String email, boolean agreeToTerms,
        int renewBeforeDays) {
}
