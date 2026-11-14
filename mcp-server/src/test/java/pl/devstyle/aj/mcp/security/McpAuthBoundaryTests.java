package pl.devstyle.aj.mcp.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end boundary tests for the MCP server's auth plumbing. A fake host (authorization server + REST API)
 * runs on a random port; the MCP server runs on another random port and is called over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAuthBoundaryTests {

    private static final String MCP_RESOURCE = "http://localhost:8181";
    private static final String CLIENT_TOKEN = "client-token-t1";
    private static final String EXCHANGED_TOKEN = "exchanged-token-t2";

    private static final List<String> backendAuthorizationHeaders = new CopyOnWriteArrayList<>();
    private static final List<String> introspectedTokens = new CopyOnWriteArrayList<>();
    private static final HttpServer fakeHost = startFakeHost();

    @LocalServerPort
    private int mcpPort;

    @DynamicPropertySource
    static void fakeHostProperties(DynamicPropertyRegistry registry) {
        var hostUrl = "http://localhost:" + fakeHost.getAddress().getPort();
        registry.add("aj.backend.url", () -> hostUrl);
        registry.add("aj.oauth.server-url", () -> hostUrl);
        registry.add("aj.mcp.base-url", () -> MCP_RESOURCE);
        registry.add("aj.oauth.client-id", () -> "aj-mcp-server");
        registry.add("aj.oauth.client-secret", () -> "test-mcp-secret");
        registry.add("management.server.port", () -> "-1");
    }

    @AfterAll
    static void stopFakeHost() {
        fakeHost.stop(0);
    }

    @BeforeEach
    void resetRecordings() {
        backendAuthorizationHeaders.clear();
        introspectedTokens.clear();
    }

    @Test
    void toolCall_withInactiveToken_returns401() throws Exception {
        var response = callListProducts("not-a-real-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(backendAuthorizationHeaders).isEmpty();
    }

    @Test
    void toolCall_withValidToken_neverForwardsClientTokenToBackend() throws Exception {
        var response = callListProducts(CLIENT_TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(backendAuthorizationHeaders)
                .isNotEmpty()
                .doesNotContain("Bearer " + CLIENT_TOKEN)
                .containsOnly("Bearer " + EXCHANGED_TOKEN);
        assertThat(introspectedTokens).contains(CLIENT_TOKEN);
    }

    private HttpResponse<String> callListProducts(String bearerToken) throws IOException, InterruptedException {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"aj_list_products","arguments":{}}}
                """;
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + mcpPort + "/"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpServer startFakeHost() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/oauth2/introspect", McpAuthBoundaryTests::handleIntrospect);
            server.createContext("/oauth2/token", McpAuthBoundaryTests::handleTokenExchange);
            server.createContext("/api/products", exchange -> {
                backendAuthorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                respond(exchange, 200, "[]");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start fake host", e);
        }
    }

    private static void handleIntrospect(HttpExchange exchange) throws IOException {
        var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var token = form.contains("token=" + CLIENT_TOKEN) ? CLIENT_TOKEN : "other";
        introspectedTokens.add(token);
        if (!CLIENT_TOKEN.equals(token)) {
            respond(exchange, 200, "{\"active\":false}");
            return;
        }
        var now = Instant.now().getEpochSecond();
        respond(exchange, 200, """
                {"active":true,"sub":"admin","client_id":"some-mcp-client","scope":"mcp:read mcp:edit",
                 "aud":["%s"],"iss":"http://localhost","iat":%d,"exp":%d,"token_type":"Bearer"}
                """.formatted(MCP_RESOURCE, now, now + 600));
    }

    private static void handleTokenExchange(HttpExchange exchange) throws IOException {
        respond(exchange, 200, """
                {"access_token":"%s","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
                 "token_type":"Bearer","expires_in":300,"scope":"mcp:read mcp:edit"}
                """.formatted(EXCHANGED_TOKEN));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
