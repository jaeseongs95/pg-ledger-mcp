package org.pgledger.admin.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OpenID Connect provider inside the test JVM (discovery document, key set and token endpoint on a loopback
 * port), so that the sign-in of the admin runs end to end, with the ID token validation of Spring Security, without any
 * connection to a real provider. The tests make the ID token the token endpoint hands out; the provider has no
 * login page (the "browser" of a test goes straight to the callback).
 */
public final class MockOidcProvider implements AutoCloseable {

    public static final String CLIENT_ID = "admin-client";
    public static final String SUBJECT = "subject-0001";

    /** The discovery document: the endpoints and the signing algorithm. */
    record Discovery(String issuer, String authorization_endpoint, String token_endpoint, String jwks_uri,
            List<String> response_types_supported, List<String> subject_types_supported,
            List<String> id_token_signing_alg_values_supported) {
    }

    /** The answer of the token endpoint. */
    record TokenAnswer(String access_token, String token_type, long expires_in, String id_token) {
    }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final RSAKey key;
    private final RSAPrivateKey privateKey;
    private final AtomicReference<String> idToken = new AtomicReference<>("");
    private final String issuer;

    private MockOidcProvider(HttpServer server, KeyPair pair) {
        this.server = server;
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        this.key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID("mock-key").build();
        this.issuer = "http://localhost:" + server.getAddress().getPort();
    }

    public static MockOidcProvider start() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            MockOidcProvider provider = new MockOidcProvider(server, generator.generateKeyPair());
            provider.routes();
            server.start();
            return provider;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has RSA", e);
        }
    }

    public String issuer() {
        return issuer;
    }

    /** The ID token that the token endpoint hands out next: the claims of a good token, changed by {@code tweak}. */
    public void issue(String nonce, Consumer<JWTClaimsSet.Builder> tweak) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).subject(SUBJECT)
                .audience(CLIENT_ID).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
                .claim("nonce", nonce).claim("email", AdminTestSettings.OWNER_EMAIL).claim("email_verified", true);
        tweak.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(privateKey));
            idToken.set(jwt.serialize());
        } catch (JOSEException e) {
            throw new IllegalStateException("the test token cannot be signed", e);
        }
    }

    private void routes() {
        server.createContext("/.well-known/openid-configuration", exchange -> reply(exchange,
                json.writeValueAsString(new Discovery(issuer, issuer + "/authorize", issuer + "/token", issuer + "/jwks",
                        List.of("code"), List.of("public"), List.of("RS256")))));
        server.createContext("/jwks", exchange -> reply(exchange, new JWKSet(key).toString()));
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            reply(exchange, json.writeValueAsString(new TokenAnswer("access-token", "Bearer", 3600, idToken.get())));
        });
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Properties of a client registration for this provider (the redirect URI is the configured external address). */
    public Map<String, String> registrationProperties(String redirectUri) {
        String registration = "spring.security.oauth2.client.registration.mock.";
        return Map.of(registration + "client-id", CLIENT_ID, registration + "client-secret", "mock-secret-value",
                registration + "authorization-grant-type", "authorization_code", registration + "scope", "openid,email",
                registration + "redirect-uri", redirectUri, registration + "client-name", "Mock provider",
                "spring.security.oauth2.client.provider.mock.issuer-uri", issuer);
    }
}
