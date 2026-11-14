package pl.devstyle.aj.mcp.security;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSecurityChainTests {

    private static final String MCP_RESOURCE = "http://localhost:8181";
    private static final String RESOURCE_METADATA_URL = MCP_RESOURCE + "/.well-known/oauth-protected-resource";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            """;

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
    }

    @Test
    void request_withoutToken_returns401WithResourceMetadataAndNoError() throws Exception {
        var response = postMcp(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .startsWith("Bearer ")
                .contains("resource_metadata=\"" + RESOURCE_METADATA_URL + "\"")
                .contains("scope=\"mcp:read mcp:edit\"")
                .doesNotContain("error=");
        assertThat(fakeHost.requests()).isEmpty();
    }

    @Test
    void request_withForeignAudienceToken_returns401InvalidToken() throws Exception {
        fakeHost.activeToken("mcp:read mcp:edit", "http://localhost:8180/api");

        var response = postMcp("foreign-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"invalid_token\"")
                .contains("resource_metadata=\"" + RESOURCE_METADATA_URL + "\"");
    }

    @Test
    void request_withTrailingSlashAudience_isAccepted() throws Exception {
        fakeHost.activeToken("mcp:read mcp:edit", MCP_RESOURCE + "/");

        var response = postMcp("slash-token");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fakeHost.requests()).singleElement()
                .satisfies(request -> assertThat(request.body()).contains("token=slash-token"));
    }

    @Test
    void request_withTokenWithoutMcpScope_returns403InsufficientScope() throws Exception {
        fakeHost.activeToken("openid profile", MCP_RESOURCE);

        var response = postMcp("unscoped-token");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"mcp:read\"")
                .contains("resource_metadata=\"" + RESOURCE_METADATA_URL + "\"");
    }

    @Test
    void request_whenIntrospectionUnavailable_returns5xxWithoutInvalidToken() throws Exception {
        fakeHost.introspectionStatus(503);

        var response = postMcp("any-token");

        assertThat(response.statusCode()).isBetween(500, 599);
        assertThat(response.headers().allValues("WWW-Authenticate")).noneMatch(h -> h.contains("invalid_token"));
        assertThat(response.body()).doesNotContain("invalid_token");
    }

    @Test
    void request_whenIntrospectionStalls_returns5xxWithoutInvalidToken() throws Exception {
        fakeHost.activeToken("mcp:read mcp:edit", MCP_RESOURCE).introspectionDelay(Duration.ofSeconds(15));

        var start = System.nanoTime();
        var response = postMcp("stalled-token");
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).isBetween(500, 599);
        assertThat(response.headers().allValues("WWW-Authenticate")).noneMatch(h -> h.contains("invalid_token"));
        assertThat(response.body()).doesNotContain("invalid_token");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void protectedResourceMetadata_resourceEqualsBaseUrl() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(mcpUrl() + ".well-known/oauth-protected-resource")).GET().build();

        var response = send(request);

        assertThat(response.statusCode()).isEqualTo(200);
        var metadata = new ObjectMapper().readTree(response.body());
        assertThat(metadata.path("resource").asText()).isEqualTo(MCP_RESOURCE);
        assertThat(metadata.path("authorization_servers").get(0).asText()).isEqualTo(fakeHost.url());
        assertThat(metadata.path("scopes_supported").toString()).isEqualTo("[\"mcp:read\",\"mcp:edit\"]");
        var mtlsBound = metadata.get("tls_client_certificate_bound_access_tokens");
        assertThat(mtlsBound).isNotNull();
        assertThat(mtlsBound.isBoolean()).isTrue();
        assertThat(mtlsBound.booleanValue()).isFalse();
    }

    private HttpResponse<String> postMcp(String bearerToken) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(mcpUrl()))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String wwwAuthenticate(HttpResponse<String> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private String mcpUrl() {
        return "http://localhost:" + mcpPort + "/";
    }
}
