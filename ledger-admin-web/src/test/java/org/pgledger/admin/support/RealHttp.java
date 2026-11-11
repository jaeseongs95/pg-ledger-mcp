package org.pgledger.admin.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain HTTP to the running admin (the JDK {@code HttpClient}, redirects not followed, cookies kept by the test) for the
 * cases that are about HTTP itself: the attributes of the session cookie, the Host header, the forwarding headers, the
 * path of a request.
 */
public final class RealHttp {

    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    public static final String SESSION_COOKIE = "LEDGER_ADMIN_SESSION";

    /** What the server answered. */
    public record Reply(int status, java.net.http.HttpHeaders headers, String body) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }

        /** The {@code Set-Cookie} line of the session cookie, if the answer sets one. */
        public Optional<String> sessionCookieLine() {
            return headers.allValues("Set-Cookie").stream().filter(line -> line.startsWith(SESSION_COOKIE + "=")).findFirst();
        }

        /** The id in the session cookie that this answer sets. */
        public Optional<String> sessionId() {
            return sessionCookieLine().map(line -> line.substring(SESSION_COOKIE.length() + 1, line.indexOf(';')));
        }

        public String csrf() {
            Matcher matcher = CSRF_FIELD.matcher(body);
            if (!matcher.find()) {
                throw new AssertionError("no CSRF field in the body");
            }
            return matcher.group(1);
        }
    }

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final int port;

    public RealHttp(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    public Reply get(String target, String... headers) {
        return send(request(target, headers).GET().build());
    }

    public Reply postForm(String target, String body, String... headers) {
        return send(request(target, headers).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private HttpRequest.Builder request(String target, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(Duration.ofSeconds(30));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return request;
    }

    private Reply send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the admin", e);
        }
    }

    /** A cookie header for a session id. */
    public static String cookie(String sessionId) {
        return SESSION_COOKIE + "=" + sessionId;
    }

    /** Signs in with the token over the form, the way a browser does; returns the session id after the sign-in. */
    public String signIn(String token) {
        Reply page = get("/login");
        String before = page.sessionId().orElseThrow();
        Reply login = postForm("/login", "username=owner&token=" + token + "&_csrf=" + page.csrf(), "Cookie", cookie(before));
        if (login.status() != 302) {
            throw new AssertionError("the sign-in answered " + login.status());
        }
        return login.sessionId().orElseThrow();
    }

}
