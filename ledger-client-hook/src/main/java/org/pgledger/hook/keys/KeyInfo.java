package org.pgledger.hook.keys;

/**
 * What the owner needs to register a session key: the author, the public key (X.509 DER in standard base64) and its
 * fingerprint (SHA-256 of the DER bytes, 64 lower-case hex characters). The private key is not reachable from here.
 */
public record KeyInfo(String author, String publicKey, String fingerprint) {
}
