package org.pgledger.hook.keys;

/**
 * What the owner needs to register a session key on a server: the server (as the host names it) and the author, the
 * public key (X.509 DER in standard base64) and its fingerprint (SHA-256 of the DER, 64 lower-case hex characters). The
 * private key is not reachable from here.
 */
public record KeyInfo(String server, String author, String publicKey, String fingerprint) {
}
