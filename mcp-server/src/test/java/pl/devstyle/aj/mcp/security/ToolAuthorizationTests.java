package pl.devstyle.aj.mcp.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-tool authorization over real HTTP: the MCP server introspects T1, checks the tool's scope, exchanges
 * T1 for T2 exactly once and calls the fake backend with T2 only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolAuthorizationTests {

    private static final String MCP_RESOURCE = "http://localhost:8181";
    private static final String CLIENT_TOKEN = "client-token-t1";
    private static final String EXCHANGED_TOKEN = "exchanged-token-t2";
    private static final String ADD_PRODUCT_ARGUMENTS = """
            {"name":"Laptop","price":1299.99,"sku":"LAP-001","categoryId":1}""";

    private static final FakeHost fakeHost = new FakeHost();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int mcpPort;

    @DynamicPropertySource
    static void fakeHostProperties(DynamicPropertyRegistry registry) {
        registry.add("aj.backend.url", fakeHost::url);
        registry.add("aj.oauth.server-url", fakeHost::url);
        registry.add("aj.mcp.base-url", () -> MCP_RESOURCE);
        registry.add("management.server.port", () -> "-1");
    }

    @AfterAll
    static void stopFakeHost() {
        fakeHost.stop();
    }

    @BeforeEach
    void resetFakeHost() {
        fakeHost.reset();
        fakeHost.activeToken("mcp:read mcp:edit", MCP_RESOURCE).exchangedToken(EXCHANGED_TOKEN);
    }

    @Test
    void addProduct_withReadOnlyToken_returnsScopeErrorWithoutExchange() throws Exception {
        fakeHost.activeToken("mcp:read", MCP_RESOURCE);

        var response = callTool("aj_add_product", ADD_PRODUCT_ARGUMENTS);

        assertToolError(response, "Insufficient scope");
        assertThat(fakeHost.requests(FakeHost.TOKEN_PATH)).isEmpty();
        assertThat(fakeHost.backendRequests()).isEmpty();
    }

    @Test
    void toolCall_validToken_exchangesExactlyOncePerCall() throws Exception {
        var listProducts = toolResult(callTool("aj_list_products", "{}"));
        var listCategories = toolResult(callTool("aj_list_categories", "{}"));
        var addProduct = toolResult(callTool("aj_add_product", ADD_PRODUCT_ARGUMENTS));

        assertThat(listProducts.path("isError").asBoolean()).isFalse();
        assertThat(listCategories.path("isError").asBoolean()).isFalse();
        assertThat(addProduct.path("isError").asBoolean()).isFalse();
        assertThat(addProduct.path("structuredContent").path("sku").asText()).isEqualTo("LAP-001");

        assertThat(fakeHost.requests(FakeHost.TOKEN_PATH)).hasSize(3)
                .allSatisfy(exchange -> assertThat(exchange.body()).contains("subject_token=" + CLIENT_TOKEN));
        assertThat(fakeHost.backendRequests()).hasSize(3)
                .extracting(FakeHost.RecordedRequest::authorization)
                .containsOnly("Bearer " + EXCHANGED_TOKEN);
        assertThat(fakeHost.backendRequests())
                .allSatisfy(request -> assertThat(request.authorization()).doesNotContain(CLIENT_TOKEN));
    }

    @Test
    void toolCall_exchangeRejected_returnsToolErrorWithoutBackendCall() throws Exception {
        fakeHost.exchangeOutcome(FakeHost.ExchangeOutcome.INVALID_SCOPE);

        var response = callTool("aj_list_products", "{}");

        assertToolError(response, "Insufficient permissions");
        assertThat(fakeHost.requests(FakeHost.TOKEN_PATH)).hasSize(1);
        assertThat(fakeHost.backendRequests()).isEmpty();
    }

    @Test
    void toolCall_wrongIssuedTokenType_returnsToolError() throws Exception {
        fakeHost.exchangeOutcome(FakeHost.ExchangeOutcome.WRONG_ISSUED_TOKEN_TYPE);

        var response = callTool("aj_list_categories", "{}");

        assertToolError(response, "Backend authorization failed");
        assertThat(fakeHost.backendRequests()).isEmpty();
    }

    @Test
    void toolCall_backend401_returnsApiErrorWithoutRetry() throws Exception {
        fakeHost.backendStatus(401);

        var response = callTool("aj_list_products", "{}");

        assertToolError(response, "Authentication required");
        assertThat(fakeHost.requests(FakeHost.TOKEN_PATH)).hasSize(1);
        assertThat(fakeHost.backendRequests()).hasSize(1);
    }

    /**
     * Tool failures keep the server's existing contract: HTTP 200 with a JSON-RPC error carrying the
     * {@code McpToolException} message.
     */
    private static void assertToolError(JsonNode response, String expectedMessage) {
        assertThat(response.has("result")).as("no tool result in %s", response).isFalse();
        assertThat(response.path("error").path("message").asText()).contains(expectedMessage);
        assertThat(response.toString()).doesNotContain(CLIENT_TOKEN).doesNotContain(EXCHANGED_TOKEN);
    }

    private static JsonNode toolResult(JsonNode response) {
        assertThat(response.has("result")).as("tool result in %s", response).isTrue();
        return response.path("result");
    }

    private JsonNode callTool(String tool, String argumentsJson) throws IOException, InterruptedException {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(tool, argumentsJson);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + mcpPort + "/"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + CLIENT_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try (var client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }
}
