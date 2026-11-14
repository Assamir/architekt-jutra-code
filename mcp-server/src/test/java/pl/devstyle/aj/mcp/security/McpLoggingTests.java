package pl.devstyle.aj.mcp.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP-15: a full tool flow (introspection, token exchange, backend call, plus the error paths) at the shipped
 * logging levels never writes T1, T2, the client secret or tool arguments to the log.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpLoggingTests {

    private static final String MCP_RESOURCE = "http://localhost:8181";
    private static final String CLIENT_TOKEN = "logging-subject-token-t1-6f1c";
    private static final String EXCHANGED_TOKEN = "logging-exchanged-token-t2-9a4e";
    private static final String CLIENT_SECRET = "test-mcp-secret";

    private static final FakeHost fakeHost = new FakeHost();

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
    void toolCall_fullFlow_neverLogsSubjectOrExchangedToken(CapturedOutput output) throws Exception {
        assertThat(callTool("aj_list_products", "{}")).contains("\"isError\":false");
        assertThat(callTool("aj_add_product", """
                {"name":"Laptop","price":1299.99,"sku":"LAP-001","categoryId":1}""")).contains("\"isError\":false");

        fakeHost.backendStatus(400);
        assertThat(callTool("aj_add_product", """
                {"name":"Laptop","price":1299.99,"sku":"LAP-001","categoryId":1}""")).contains("\"error\":");
        fakeHost.backendStatus(200).exchangeOutcome(FakeHost.ExchangeOutcome.INVALID_SCOPE);
        assertThat(callTool("aj_list_products", "{}")).contains("Insufficient permissions");

        var basicCredentials = fakeHost.requests(FakeHost.TOKEN_PATH).getFirst().authorization();
        assertThat(basicCredentials).startsWith("Basic ");
        assertThat(output.getAll())
                .contains("Token exchange rejected by authorization server: status=400, error=invalid_scope")
                .doesNotContain(CLIENT_TOKEN)
                .doesNotContain(EXCHANGED_TOKEN)
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain("LAP-001")
                .doesNotContain(basicCredentials.substring("Basic ".length()))
                .doesNotContain(Base64.getEncoder().encodeToString(CLIENT_TOKEN.getBytes(StandardCharsets.UTF_8)));
    }

    private String callTool(String tool, String argumentsJson) throws IOException, InterruptedException {
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
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }
}
