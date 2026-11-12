package org.pgledger.cli.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.pgledger.cli.deploy.DeployLayout;
import org.pgledger.cli.secret.ProcessUser;
import org.pgledger.cli.secret.SecretProtection;
import org.springframework.stereotype.Component;

/**
 * The files of the TLS directory and of the web root: the account key ({@code account.key}), the certificate chain
 * ({@code fullchain.pem}) and the key of the certificate ({@code privkey.pem}), and the token files of the HTTP-01 proof. The
 * keys are made with the JDK (EC P-256) and written as standard PEM: {@code privkey.pem} is one PKCS #8 {@code PRIVATE KEY}
 * block (any proxy reads it), {@code account.key} that block and the {@code PUBLIC KEY} of it (the public key of an EC key
 * cannot be derived from the private one with the JDK, and the ACME library wants the pair). Both keys are created owner-only
 * with the protection of the secret store ({@link SecretProtection}) and never printed. {@code fullchain.pem} and the token
 * files are readable by others: the web server reads them. Every file is replaced atomically ({@link AtomicFile}), and the
 * certificate and its key are staged together, so that the failures of a disk come before the first replacement.
 */
@Component
class TlsFiles {

    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final FileAttribute<?> WEB_DIRECTORY = PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"));
    /** The subject alternative name type of a DNS name (RFC 5280). */
    private static final int DNS_NAME = 2;

    private final ProcessUser processUser;

    TlsFiles(ProcessUser processUser) {
        this.processUser = processUser;
    }

    Path accountKey(Path directory) {
        return directory.resolve(DeployLayout.TLS_ACCOUNT_FILE);
    }

    Path chain(Path directory) {
        return directory.resolve(DeployLayout.TLS_CHAIN_FILE);
    }

    Path privateKey(Path directory) {
        return directory.resolve(DeployLayout.TLS_KEY_FILE);
    }

    boolean hasAccountKey(Path directory) {
        return Files.isRegularFile(accountKey(directory), LinkOption.NOFOLLOW_LINKS);
    }

    /** A new EC P-256 key pair, made with the JDK. */
    KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            // every Java runtime has EC and the curve P-256: a runtime without is not one this tool can run on
            throw new IllegalStateException(e);
        }
    }

    /** Creates the TLS directory (owner only) when it is not there. */
    void ensureDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            SecretProtection protection = SecretProtection.of(directory, processUser);
            Files.createDirectories(directory, protection.directoryAttributes());
            protection.settle(directory);
        }
    }

    KeyPair readAccountKey(Path directory) throws IOException {
        Map<String, byte[]> blocks = blocks(Files.readString(accountKey(directory), StandardCharsets.US_ASCII));
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(required(blocks, "PRIVATE KEY")));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(required(blocks, "PUBLIC KEY")));
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException e) {
            throw new IOException("the account key is not a PKCS #8 EC key with its public key", e);
        }
    }

    /** Writes the account key, owner only. */
    void writeAccountKey(Path directory, KeyPair keys) throws IOException {
        byte[] content = (pem("PRIVATE KEY", keys.getPrivate().getEncoded()) + pem("PUBLIC KEY", keys.getPublic().getEncoded()))
                .getBytes(StandardCharsets.US_ASCII);
        try (AtomicFile.Staged staged = AtomicFile.stagePrivate(accountKey(directory), SecretProtection.of(directory, processUser),
                out -> out.write(content))) {
            staged.commit();
        }
    }

    /**
     * Puts the new certificate in place: the chain (as the authority sent it, leaf first) and its key. Both are written
     * and flushed under temporary names first; only then is the first one replaced, so a failure of the disk leaves both old
     * files as they were.
     */
    void writeCertificate(Path directory, String chainPem, PrivateKey key) throws IOException {
        byte[] chain = chainPem.getBytes(StandardCharsets.US_ASCII);
        byte[] privateKey = pem("PRIVATE KEY", key.getEncoded()).getBytes(StandardCharsets.US_ASCII);
        try (AtomicFile.Staged stagedKey = AtomicFile.stagePrivate(privateKey(directory),
                SecretProtection.of(directory, processUser), out -> out.write(privateKey));
                AtomicFile.Staged stagedChain = AtomicFile.stageShared(chain(directory), out -> out.write(chain))) {
            stagedKey.commit();
            stagedChain.commit();
        }
    }

    /** The first certificate of {@code fullchain.pem} (the one of the host names), or empty when there is none or it cannot be read. */
    Optional<X509Certificate> readCertificate(Path directory) throws IOException {
        Path file = chain(directory);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return firstCertificate(in);
        }
    }

    /** The first certificate of a PEM chain as the authority sent it, or empty when the text holds none. */
    Optional<X509Certificate> firstCertificate(String chainPem) throws IOException {
        return firstCertificate(new ByteArrayInputStream(chainPem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static Optional<X509Certificate> firstCertificate(InputStream in) throws IOException {
        try {
            Collection<? extends java.security.cert.Certificate> found = CertificateFactory.getInstance("X.509")
                    .generateCertificates(in);
            return found.stream().filter(X509Certificate.class::isInstance).map(X509Certificate.class::cast).findFirst();
        } catch (CertificateException e) {
            // a text that is not a certificate is no certificate: a file of it is replaced, an answer of it is refused
            return Optional.empty();
        }
    }

    /** The DNS names of the certificate, lower case. */
    Set<String> names(X509Certificate certificate) {
        Set<String> names = new TreeSet<>();
        try {
            Collection<List<?>> alternative = certificate.getSubjectAlternativeNames();
            if (alternative != null) {
                for (List<?> entry : alternative) {
                    if (entry.getFirst() instanceof Integer type && type == DNS_NAME) {
                        names.add(String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (CertificateException e) {
            // an unreadable name list names nothing: the certificate does not cover the hosts and is renewed
            return Set.of();
        }
        return names;
    }

    /**
     * Writes the token file of the proof: {@code <webroot>/.well-known/acme-challenge/<token>} with the key authorization
     * and nothing else. The token comes from the certificate authority and becomes a file name, so it has to be a plain token
     * (the base64url alphabet of RFC 8555, section 8.1: no separator, no dot): nothing else is written.
     *
     * @return the file, or empty when the token is not plain (nothing was written)
     */
    Optional<Path> publishToken(Path webroot, String token, String keyAuthorization) throws IOException {
        if (!isPlainToken(token)) {
            return Optional.empty();
        }
        Path directory = webroot.resolve(".well-known").resolve("acme-challenge");
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory, WEB_DIRECTORY);
        } else {
            Files.createDirectories(directory);
        }
        Path file = directory.resolve(token);
        byte[] content = keyAuthorization.getBytes(StandardCharsets.US_ASCII);
        AtomicFile.replaceShared(file, out -> out.write(content));
        return Optional.of(file);
    }

    /** Whether the token is made of the base64url alphabet only, and not empty or longer than any real token. */
    static boolean isPlainToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der) + "\n-----END "
                + type + "-----\n";
    }

    private static Map<String, byte[]> blocks(String text) {
        Map<String, byte[]> blocks = new HashMap<>();
        Matcher matcher = BLOCK.matcher(text);
        while (matcher.find()) {
            blocks.put(matcher.group(1), Base64.getMimeDecoder().decode(matcher.group(2)));
        }
        return blocks;
    }

    private static byte[] required(Map<String, byte[]> blocks, String type) throws IOException {
        byte[] block = blocks.get(type);
        if (block == null) {
            throw new IOException("the account key has no " + type + " block");
        }
        return block;
    }
}
