package org.pgledger.cli.tls;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A TEST DOUBLE of a certificate authority, not a certificate authority: an in-process HTTP server that speaks the endpoints
 * of RFC 8555 that the client of {@code tls-issue} and {@code tls-renew} uses (directory, new-nonce, new-account, new-order,
 * authorization, challenge, finalize, order, certificate) and nothing else, so the flow of the client can be tested without a
 * network. It checks what a real authority checks of the requests it sees (the JWS: algorithm, nonce used once, the {@code url}
 * of the request, the signature with the key of the {@code jwk} or of the account; the key authorization of the HTTP-01 proof,
 * read from the token file in the web root at the moment the challenge is validated; the signature, the names and the key of
 * the certificate request) and issues a certificate of the names of the request from a key of its own. It validates no
 * domain, rate limit or policy of a real authority: the real authority is verified on the test server against the Let's
 * Encrypt staging directory, not here.
 */
final class StubCertificateAuthority implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /** One request the double has seen: the method and the path, in order. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Set<String> nonces = ConcurrentHashMap.newKeySet();
    private final Map<String, PublicKey> accounts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> accountBodies = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Authz> authorizations = new ConcurrentHashMap<>();
    private final Map<String, String> thumbprints = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> newAccountHeaders = new CopyOnWriteArrayList<>();
    private final List<String> tokenFileContents = new CopyOnWriteArrayList<>();
    private final List<Boolean> tokenFileExistedAtValidation = new CopyOnWriteArrayList<>();
    private final List<String> tokenModes = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final HttpServer server;
    private final Path webroot;
    private final Clock clock;
    private final KeyPair caKeys;
    private final X509Certificate caCertificate;

    // what a test may change
    private volatile long validityDays = 90;
    private volatile String offeredToken;
    private volatile boolean invalidateAuthorization;
    private volatile int finalizeStatus = 200;
    private volatile int certificateStatus = 200;
    private volatile int badNonces;
    private volatile List<String> certificateNames;

    private static final class Order {
        String id;
        List<String> names;
        List<String> authorizationIds = new ArrayList<>();
        String status = "pending";
        String chainPem;
    }

    private static final class Authz {
        String id;
        String name;
        String token;
        String status = "pending";
        String challengeStatus = "pending";
    }

    StubCertificateAuthority(Path webroot, Clock clock) throws IOException, GeneralSecurityException, OperatorCreationException {
        this.webroot = webroot;
        this.clock = clock;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        this.caKeys = generator.generateKeyPair();
        X500Name caName = new X500Name("CN=Stub Test Authority");
        Instant now = clock.instant();
        this.caCertificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(caName, BigInteger.ONE,
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(3650))), caName, caKeys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate())));
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The directory URL of the double (loopback http: the one http the client accepts). */
    String directoryUrl() {
        return base() + "/directory";
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---- what a test changes ----

    void validityDays(long days) {
        this.validityDays = days;
    }

    void offerToken(String token) {
        this.offeredToken = token;
    }

    void invalidateAuthorizations() {
        this.invalidateAuthorization = true;
    }

    void failFinalize(int status) {
        this.finalizeStatus = status;
    }

    void failCertificateDownload(int status) {
        this.certificateStatus = status;
    }

    /** The next {@code count} signed requests are refused as {@code badNonce}. */
    void refuseNonces(int count) {
        this.badNonces = count;
    }

    /** The certificate is issued for these names instead of those of the request. */
    void issueForNames(List<String> names) {
        this.certificateNames = names;
    }

    // ---- what a test reads ----

    List<String> requests() {
        return List.copyOf(requests);
    }

    int requestCount() {
        return requests.size();
    }

    /** The decoded protected headers of every new-account request (the key is in {@code jwk}). */
    List<Map<String, Object>> newAccountHeaders() {
        return List.copyOf(newAccountHeaders);
    }

    /** The bodies of the accounts that were created, in order. */
    List<Map<String, Object>> accountBodies() {
        return List.copyOf(accountBodies.values());
    }

    int accountsCreated() {
        return accountBodies.size();
    }

    /** The content of each token file at the moment its challenge was validated; empty files are recorded as an empty text. */
    List<String> tokenFileContents() {
        return List.copyOf(tokenFileContents);
    }

    /** On a file system with POSIX modes: the mode of each token file and of its directory at validation, as {@code "rw-r--r-- rwxr-xr-x"}. */
    List<String> tokenModes() {
        return List.copyOf(tokenModes);
    }

    List<Boolean> tokenFileExistedAtValidation() {
        return List.copyOf(tokenFileExistedAtValidation);
    }

    /** The key authorization the double expects for the account of the first order (token, a dot and the thumbprint of the key). */
    List<String> expectedKeyAuthorizations() {
        List<String> expected = new ArrayList<>();
        for (Authz authz : authorizations.values()) {
            expected.add(authz.token + "." + thumbprints.values().stream().findFirst().orElse(""));
        }
        return expected;
    }

    /** The PEM chain of the latest order that was issued, or null. */
    String lastChain() {
        return orders.values().stream().map(order -> order.chainPem).filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElse(null);
    }

    /** The names the double was ordered, in the order of the orders. */
    List<List<String>> orderedNames() {
        return orders.values().stream().map(order -> order.names).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- the protocol ----

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        try {
            if ("/directory".equals(path)) {
                reply(exchange, 200, "application/json", JSON.writeValueAsString(directory()), null);
            } else if ("/new-nonce".equals(path)) {
                reply(exchange, "HEAD".equals(method) ? 200 : 204, "application/json", "", null);
            } else {
                signed(exchange, path);
            }
        } catch (GeneralSecurityException | OperatorCreationException | PKCSException e) {
            problem(exchange, 500, "serverInternal", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> directory() {
        Map<String, Object> directory = new LinkedHashMap<>();
        directory.put("newNonce", base() + "/new-nonce");
        directory.put("newAccount", base() + "/new-acct");
        directory.put("newOrder", base() + "/new-order");
        directory.put("meta", Map.of("termsOfService", base() + "/terms"));
        return directory;
    }

    private void signed(HttpExchange exchange, String path) throws IOException, GeneralSecurityException, OperatorCreationException, PKCSException {
        Map<String, Object> jws = JSON.readValue(exchange.getRequestBody().readAllBytes(), MAP);
        Map<String, Object> header = JSON.readValue(decode((String) jws.get("protected")), MAP);
        String url = base() + path;
        if (badNonces > 0) {
            badNonces--;
            problem(exchange, 400, "badNonce", "refused by the double");
            return;
        }
        if (!"ES256".equals(header.get("alg")) || !url.equals(header.get("url")) || !nonces.remove((String) header.get("nonce"))) {
            problem(exchange, 400, "malformed", "the protected header is wrong (alg, url or nonce)");
            return;
        }
        PublicKey key;
        if (header.containsKey("jwk")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> jwk = (Map<String, Object>) header.get("jwk");
            key = publicKey(jwk);
            thumbprints.put((String) header.get("nonce"), thumbprint(jwk));
        } else {
            key = accounts.get((String) header.get("kid"));
            if (key == null) {
                problem(exchange, 401, "unauthorized", "unknown account");
                return;
            }
        }
        String signingInput = jws.get("protected") + "." + jws.get("payload");
        if (!verify(key, signingInput, (String) jws.get("signature"))) {
            problem(exchange, 400, "malformed", "bad signature");
            return;
        }
        String payloadText = decode((String) jws.get("payload"));
        Map<String, Object> payload = payloadText.isEmpty() ? Map.of() : JSON.readValue(payloadText, MAP);
        route(exchange, path, header, key, payload);
    }

    private void route(HttpExchange exchange, String path, Map<String, Object> header, PublicKey key, Map<String, Object> payload)
            throws IOException, GeneralSecurityException, OperatorCreationException, PKCSException {
        if ("/new-acct".equals(path)) {
            newAccount(exchange, header, key, payload);
        } else if ("/new-order".equals(path)) {
            newOrder(exchange, payload);
        } else if (path.startsWith("/authz/")) {
            Authz authz = authorizations.get(path.substring("/authz/".length()));
            reply(exchange, 200, "application/json", JSON.writeValueAsString(authzJson(authz)), null);
        } else if (path.startsWith("/chall/")) {
            challenge(exchange, path.substring("/chall/".length()));
        } else if (path.startsWith("/order/")) {
            Order order = orders.get(path.substring("/order/".length()));
            refresh(order);
            reply(exchange, 200, "application/json", JSON.writeValueAsString(orderJson(order)), null);
        } else if (path.startsWith("/finalize/")) {
            finalizeOrder(exchange, orders.get(path.substring("/finalize/".length())), payload);
        } else if (path.startsWith("/cert/")) {
            Order order = orders.get(path.substring("/cert/".length()));
            if (certificateStatus != 200) {
                problem(exchange, certificateStatus, "serverInternal", "the double fails the download");
            } else {
                reply(exchange, 200, "application/pem-certificate-chain", order.chainPem, null);
            }
        } else {
            problem(exchange, 404, "malformed", "no such resource");
        }
    }

    private void newAccount(HttpExchange exchange, Map<String, Object> header, PublicKey key, Map<String, Object> payload)
            throws IOException {
        String existing = accounts.entrySet().stream().filter(entry -> entry.getValue().equals(key)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (existing != null) {
            reply(exchange, 200, "application/json", JSON.writeValueAsString(account(accountBodies.get(existing))), existing);
            return;
        }
        if (Boolean.TRUE.equals(payload.get("onlyReturnExisting"))) {
            problem(exchange, 400, "accountDoesNotExist", "no account for this key");
            return;
        }
        newAccountHeaders.add(header);
        String kid = base() + "/acct/" + counter.incrementAndGet();
        accounts.put(kid, key);
        Map<String, Object> body = new LinkedHashMap<>(payload);
        accountBodies.put(kid, body);
        reply(exchange, 201, "application/json", JSON.writeValueAsString(account(body)), kid);
    }

    private static Map<String, Object> account(Map<String, Object> body) {
        Map<String, Object> account = new LinkedHashMap<>(body);
        account.put("status", "valid");
        return account;
    }

    @SuppressWarnings("unchecked")
    private void newOrder(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        Order order = new Order();
        order.id = String.valueOf(counter.incrementAndGet());
        order.names = new ArrayList<>();
        for (Map<String, Object> identifier : (List<Map<String, Object>>) payload.get("identifiers")) {
            String name = (String) identifier.get("value");
            order.names.add(name);
            Authz authz = new Authz();
            authz.id = String.valueOf(counter.incrementAndGet());
            authz.name = name;
            authz.token = offeredToken != null ? offeredToken : UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            authorizations.put(authz.id, authz);
            order.authorizationIds.add(authz.id);
        }
        orders.put(order.id, order);
        reply(exchange, 201, "application/json", JSON.writeValueAsString(orderJson(order)), base() + "/order/" + order.id);
    }

    private Map<String, Object> orderJson(Order order) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("status", order.status);
        List<Map<String, String>> identifiers = new ArrayList<>();
        order.names.forEach(name -> identifiers.add(Map.of("type", "dns", "value", name)));
        json.put("identifiers", identifiers);
        json.put("authorizations", order.authorizationIds.stream().map(id -> base() + "/authz/" + id).toList());
        json.put("finalize", base() + "/finalize/" + order.id);
        if (order.chainPem != null) {
            json.put("certificate", base() + "/cert/" + order.id);
        }
        return json;
    }

    private Map<String, Object> authzJson(Authz authz) {
        Map<String, Object> challenge = new LinkedHashMap<>();
        challenge.put("type", "http-01");
        challenge.put("url", base() + "/chall/" + authz.id);
        challenge.put("token", authz.token);
        challenge.put("status", authz.challengeStatus);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("status", authz.status);
        json.put("identifier", Map.of("type", "dns", "value", authz.name));
        json.put("challenges", List.of(challenge));
        return json;
    }

    private void refresh(Order order) {
        boolean allValid = order.authorizationIds.stream().allMatch(id -> "valid".equals(authorizations.get(id).status));
        if ("pending".equals(order.status) && allValid) {
            order.status = "ready";
        }
    }

    /** The validation of a real authority: it fetches the token file over http; the double reads it from the web root. */
    @SuppressWarnings("unchecked")
    private void challenge(HttpExchange exchange, String id) throws IOException, GeneralSecurityException {
        Authz authz = authorizations.get(id);
        Path file = webroot.resolve(".well-known").resolve("acme-challenge").resolve(authz.token);
        boolean exists = Files.isRegularFile(file);
        tokenFileExistedAtValidation.add(exists);
        if (exists && file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            tokenModes.add(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) + " "
                    + java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        }
        String content = exists ? Files.readString(file, StandardCharsets.US_ASCII) : "";
        tokenFileContents.add(content);
        String expected = authz.token + "." + thumbprints.values().stream().findFirst().orElse("");
        boolean good = !invalidateAuthorization && expected.equals(content);
        authz.status = good ? "valid" : "invalid";
        authz.challengeStatus = good ? "valid" : "invalid";
        Map<String, Object> challenge = (Map<String, Object>) ((List<?>) authzJson(authz).get("challenges")).getFirst();
        reply(exchange, 200, "application/json", JSON.writeValueAsString(challenge), null);
    }

    private void finalizeOrder(HttpExchange exchange, Order order, Map<String, Object> payload)
            throws IOException, GeneralSecurityException, OperatorCreationException, PKCSException {
        if (finalizeStatus != 200) {
            problem(exchange, finalizeStatus, "serverInternal", "the double fails the finalize");
            return;
        }
        refresh(order);
        if (!"ready".equals(order.status)) {
            problem(exchange, 403, "orderNotReady", "the order is not ready");
            return;
        }
        PKCS10CertificationRequest csr = new PKCS10CertificationRequest(Base64.getUrlDecoder().decode((String) payload.get("csr")));
        if (!csr.isSignatureValid(new org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder()
                .build(csr.getSubjectPublicKeyInfo()))) {
            problem(exchange, 400, "badCSR", "the signature of the request is wrong");
            return;
        }
        List<String> csrNames = namesOf(csr);
        if (!csrNames.containsAll(order.names) || !order.names.containsAll(csrNames)) {
            problem(exchange, 400, "badCSR", "the names of the request are not those of the order");
            return;
        }
        PublicKey subjectKey = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().getPublicKey(csr.getSubjectPublicKeyInfo());
        List<String> names = certificateNames != null ? certificateNames : order.names;
        order.chainPem = pem(issue(names, subjectKey, validityDays)) + pem(caCertificate);
        order.status = "valid";
        reply(exchange, 200, "application/json", JSON.writeValueAsString(orderJson(order)), null);
    }

    private static List<String> namesOf(PKCS10CertificationRequest csr) {
        List<String> names = new ArrayList<>();
        for (Attribute attribute : csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)) {
            Extensions extensions = Extensions.getInstance(attribute.getAttrValues().getObjectAt(0));
            for (GeneralName name : GeneralNames.fromExtensions(extensions, Extension.subjectAlternativeName).getNames()) {
                names.add(name.getName().toString());
            }
        }
        return names;
    }

    /**
     * A chain (leaf, then the issuer of the double) for these names that is valid for this many days from the clock of the double,
     * made for a key of its own: the certificate that is "in place" in a test of the renewal.
     */
    String newChain(List<String> names, long days) throws GeneralSecurityException, OperatorCreationException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return pem(issue(names, generator.generateKeyPair().getPublic(), days)) + pem(caCertificate);
    }

    private X509Certificate issue(List<String> names, PublicKey subjectKey, long days) throws GeneralSecurityException,
            OperatorCreationException, IOException {
        Instant now = clock.instant();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name("CN=Stub Test Authority"),
                BigInteger.valueOf(counter.incrementAndGet() + 1000L), Date.from(now.minus(Duration.ofHours(1))),
                Date.from(now.plus(Duration.ofDays(days))), new X500Name("CN=" + names.getFirst()), subjectKey);
        GeneralName[] general = names.stream().map(name -> new GeneralName(GeneralName.dNSName, name)).toArray(GeneralName[]::new);
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(general));
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate())));
    }

    private static String pem(X509Certificate certificate) throws GeneralSecurityException {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    // ---- JWS and keys ----

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8).strip();
    }

    private static PublicKey publicKey(Map<String, Object> jwk) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec parameters = ((ECPublicKey) generator.generateKeyPair().getPublic()).getParams();
        ECPoint point = new ECPoint(new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("x"))),
                new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("y"))));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, parameters));
    }

    /** RFC 7638: the SHA-256 of the members crv, kty, x, y in this order, without white space. */
    private static String thumbprint(Map<String, Object> jwk) throws GeneralSecurityException {
        String canonical = "{\"crv\":\"" + jwk.get("crv") + "\",\"kty\":\"" + jwk.get("kty") + "\",\"x\":\"" + jwk.get("x") + "\",\"y\":\""
                + jwk.get("y") + "\"}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** ES256 signatures are the raw r and s of 32 bytes each (RFC 7518 section 3.4); the JDK verifies the DER form. */
    private static boolean verify(PublicKey key, String signingInput, String signature) throws GeneralSecurityException {
        byte[] raw = Base64.getUrlDecoder().decode(signature);
        if (raw.length != 64) {
            return false;
        }
        byte[] r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] body = new byte[6 + r.length + s.length];
        body[0] = 0x30;
        body[1] = (byte) (4 + r.length + s.length);
        body[2] = 0x02;
        body[3] = (byte) r.length;
        System.arraycopy(r, 0, body, 4, r.length);
        body[4 + r.length] = 0x02;
        body[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, body, 6 + r.length, s.length);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(body);
    }

    // ---- answers ----

    private void problem(HttpExchange exchange, int status, String type, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:ietf:params:acme:error:" + type);
        problem.put("detail", detail);
        reply(exchange, status, "application/problem+json", JSON.writeValueAsString(problem), null);
    }

    private void reply(HttpExchange exchange, int status, String contentType, String body, String location) throws IOException {
        String nonce = UUID.randomUUID().toString();
        nonces.add(nonce);
        exchange.getResponseHeaders().add("Replay-Nonce", nonce);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        boolean noBody = status == 204 || "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, noBody ? -1 : bytes.length);
        if (!noBody) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
