package org.pgledger.admin.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import org.pgledger.attestation.PublicKeys;
import org.springframework.util.MultiValueMap;

/** The key pair a session would make (the hook command {@code session-key}) and what the owner sees of it. */
public final class AuthorKeys {

    private AuthorKeys() {
    }

    /** A key pair with the public key as the hook prints it and its fingerprint as the owner compares it. */
    public record Pair(KeyPair pair, String publicKey, String fingerprint) {

        /** The fields of the issue form for this key (the paste). */
        public MultiValueMap<String, String> pasted(MultiValueMap<String, String> fields) {
            fields.add("publicKey", publicKey);
            return fields;
        }

        /** The four lines that the hook command session-key prints for this key (server, author, public-key, fingerprint). */
        public String block(String server, String author) {
            return "server: " + server + "\nauthor: " + author + "\npublic-key: " + publicKey + "\nfingerprint: "
                    + fingerprint + "\n";
        }

        /** The fields of the issue form with the whole block pasted where the key is asked for. */
        public MultiValueMap<String, String> pastedBlock(MultiValueMap<String, String> fields, String server,
                String author) {
            fields.add("publicKey", block(server, author));
            return fields;
        }

        /** The fields of the confirmation form: the key, the fingerprint the owner saw, and the comparison box. */
        public MultiValueMap<String, String> confirmed(MultiValueMap<String, String> fields) {
            pasted(fields);
            fields.add("fingerprint", fingerprint);
            fields.add("confirmFingerprint", "yes");
            return fields;
        }
    }

    public static Pair newPair() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new Pair(pair, PublicKeys.encode(pair.getPublic()), PublicKeys.fingerprint(pair.getPublic()));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("This JDK has no Ed25519 key pair generator", failure);
        }
    }
}
