package pl.devstyle.aj.mcp.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal fake of the aj host for MCP security tests: authorization server (introspection, token exchange)
 * and REST API (products, categories). Runs on a random port; every answer is programmable per test and
 * every request is recorded with its path, method, {@code Authorization} header and body.
 */
class FakeHost {

    static final String INTROSPECT_PATH = "/oauth2/introspect";
    static final String TOKEN_PATH = "/oauth2/token";
    static final String PRODUCTS_PATH = "/api/products";
    static final String CATEGORIES_PATH = "/api/categories";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    enum ExchangeOutcome {
        SUCCESS,
        INVALID_SCOPE,
        WRONG_ISSUED_TOKEN_TYPE
    }

    record RecordedRequest(String path, String method, String authorization, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private volatile boolean active;
    private volatile List<String> audience;
    private volatile String scope;
    private volatile int introspectionStatus;
    private volatile Duration introspectionDelay;
    private volatile ExchangeOutcome exchangeOutcome;
    private volatile String exchangedToken;
    private volatile int backendStatus;

    FakeHost() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start fake host", e);
        }
        server.setExecutor(executor);
        server.createContext(INTROSPECT_PATH, this::handleIntrospect);
        server.createContext(TOKEN_PATH, this::handleTokenExchange);
        server.createContext(PRODUCTS_PATH, this::handleProducts);
        server.createContext(CATEGORIES_PATH, this::handleCategories);
        server.start();
        reset();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    void reset() {
        requests.clear();
        active = true;
        audience = List.of();
        scope = "mcp:read mcp:edit";
        introspectionStatus = 200;
        introspectionDelay = Duration.ZERO;
        exchangeOutcome = ExchangeOutcome.SUCCESS;
        exchangedToken = "exchanged-token-t2";
        backendStatus = 200;
    }

    FakeHost activeToken(String scope, String... audience) {
        this.active = true;
        this.scope = scope;
        this.audience = List.of(audience);
        return this;
    }

    FakeHost inactiveToken() {
        this.active = false;
        return this;
    }

    FakeHost introspectionStatus(int status) {
        this.introspectionStatus = status;
        return this;
    }

    FakeHost introspectionDelay(Duration delay) {
        this.introspectionDelay = delay;
        return this;
    }

    FakeHost exchangeOutcome(ExchangeOutcome outcome) {
        this.exchangeOutcome = outcome;
        return this;
    }

    FakeHost exchangedToken(String token) {
        this.exchangedToken = token;
        return this;
    }

    /**
     * Status for every {@code /api/*} call; anything but 200/201 answers with a JSON error body.
     */
    FakeHost backendStatus(int status) {
        this.backendStatus = status;
        return this;
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    List<RecordedRequest> requests(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    List<RecordedRequest> backendRequests() {
        return requests.stream().filter(request -> request.path().startsWith("/api/")).toList();
    }

    private void handleIntrospect(HttpExchange exchange) throws IOException {
        record(exchange);
        sleep(introspectionDelay);
        if (introspectionStatus != 200) {
            respond(exchange, introspectionStatus, "{\"error\":\"server_error\"}");
            return;
        }
        if (!active) {
            respond(exchange, 200, "{\"active\":false}");
            return;
        }
        var now = Instant.now().getEpochSecond();
        var aud = audience.stream().map(value -> "\"" + value + "\"").toList();
        respond(exchange, 200, """
                {"active":true,"sub":"admin","client_id":"some-mcp-client","scope":"%s",
                 "aud":%s,"iss":"http://localhost","iat":%d,"exp":%d,"token_type":"Bearer"}
                """.formatted(scope, aud, now, now + 600));
    }

    private void handleTokenExchange(HttpExchange exchange) throws IOException {
        record(exchange);
        switch (exchangeOutcome) {
            case INVALID_SCOPE -> respond(exchange, 400, "{\"error\":\"invalid_scope\"}");
            case WRONG_ISSUED_TOKEN_TYPE -> respond(exchange, 200, """
                    {"access_token":"%s","issued_token_type":"urn:ietf:params:oauth:token-type:jwt",
                     "token_type":"Bearer","expires_in":300,"scope":"READ"}
                    """.formatted(exchangedToken));
            case SUCCESS -> respond(exchange, 200, """
                    {"access_token":"%s","issued_token_type":"%s",
                     "token_type":"Bearer","expires_in":300,"scope":"READ EDIT"}
                    """.formatted(exchangedToken, ACCESS_TOKEN_TYPE));
        }
    }

    private void handleProducts(HttpExchange exchange) throws IOException {
        record(exchange);
        if (backendStatus != 200) {
            respondBackendError(exchange);
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 201, """
                    {"id":7,"name":"Laptop","price":1299.99,"sku":"LAP-001",
                     "category":{"id":1,"name":"Electronics"}}
                    """);
            return;
        }
        respond(exchange, 200, "[]");
    }

    private void handleCategories(HttpExchange exchange) throws IOException {
        record(exchange);
        if (backendStatus != 200) {
            respondBackendError(exchange);
            return;
        }
        respond(exchange, 200, "[]");
    }

    private void respondBackendError(HttpExchange exchange) throws IOException {
        respond(exchange, backendStatus, "{\"status\":%d,\"error\":\"backend error\"}".formatted(backendStatus));
    }

    private void record(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            exchange.close();
        }
    }
}
