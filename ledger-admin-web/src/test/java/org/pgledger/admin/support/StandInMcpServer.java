package org.pgledger.admin.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The health endpoint of an MCP server, inside the test JVM on a loopback port, answering the way the test says (up,
 * 503, "down" with 200, a page that is not a health report, no answer at all) and noting what it was asked. The admin
 * under test talks to it over real HTTP; the real MCP server is a deployment concern (ADM-025).
 */
public final class StandInMcpServer implements AutoCloseable {

    /** How the health endpoint answers. */
    public enum Mode {
        UP, SERVICE_UNAVAILABLE, REPORTS_DOWN, NOT_A_REPORT, HANGS
    }

    /** One request that reached the stand-in: its target and the names of its headers (lower case). */
    public record Seen(String target, List<String> headerNames) {
    }

    private final HttpServer server;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.UP);
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    private StandInMcpServer(HttpServer server) {
        this.server = server;
    }

    public static StandInMcpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StandInMcpServer stand = new StandInMcpServer(server);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/healthz", stand::answer);
            server.start();
            return stand;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The health URL, with a query so that a test can see that exactly this URL is asked. */
    public String healthUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/healthz?probe=1";
    }

    public void answer(Mode newMode) {
        mode.set(newMode);
    }

    public List<Seen> seen() {
        return List.copyOf(seen);
    }

    private void answer(HttpExchange exchange) throws IOException {
        seen.add(new Seen(exchange.getRequestURI().toString(), exchange.getRequestHeaders().keySet().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList()));
        switch (mode.get()) {
            case UP -> reply(exchange, 200, "application/json", "{\"status\":\"UP\"}");
            case SERVICE_UNAVAILABLE -> reply(exchange, 503, "application/json", "{\"status\":\"DOWN\"}");
            case REPORTS_DOWN -> reply(exchange, 200, "application/json", "{\"status\":\"DOWN\"}");
            case NOT_A_REPORT -> reply(exchange, 200, "text/html", "<html><body>login</body></html>");
            case HANGS -> hang(exchange);
        }
    }

    private static void hang(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of(type));
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
