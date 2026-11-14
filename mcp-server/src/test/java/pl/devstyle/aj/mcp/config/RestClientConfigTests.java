package pl.devstyle.aj.mcp.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.devstyle.aj.mcp.client.AjApiClient;
import pl.devstyle.aj.mcp.exception.McpToolException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTests {

    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private HttpServer backend;
    private int responseStatus;
    private AjApiClient ajApiClient;

    @BeforeEach
    void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        backend.createContext("/api/categories", this::handleCategories);
        backend.start();
        var properties = new AjProperties(
                new AjProperties.Backend("http://localhost:" + backend.getAddress().getPort() + "/"),
                new AjProperties.Oauth("http://localhost:8180", "aj-mcp-server", "test-secret"),
                new AjProperties.Mcp("http://localhost:8181"));
        var config = new RestClientConfig(properties);
        ajApiClient = config.ajApiClient(config.ajRestClient());
    }

    @AfterEach
    void stopBackend() {
        backend.stop(0);
    }

    @Test
    void ajApiClient_explicitAuthorization_isSentVerbatim() {
        responseStatus = 200;

        var categories = ajApiClient.listCategories("Bearer exchanged-token");

        assertThat(categories).isEmpty();
        assertThat(authorizationHeaders).containsExactly("Bearer exchanged-token");
    }

    @Test
    void ajApiClient_backend401_mapsToApiErrorWithoutRetry() {
        responseStatus = 401;

        assertThatThrownBy(() -> ajApiClient.listCategories("Bearer expired-token"))
                .isInstanceOfSatisfying(McpToolException.class, ex -> {
                    assertThat(ex.getErrorType()).isEqualTo(McpToolException.ErrorType.API_ERROR);
                    assertThat(ex.getMessage()).isEqualTo("Authentication required");
                });
        assertThat(authorizationHeaders).hasSize(1);
    }

    private void handleCategories(HttpExchange exchange) throws IOException {
        authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        var bytes = (responseStatus == 200 ? "[]" : "{\"error\":\"unauthorized\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
