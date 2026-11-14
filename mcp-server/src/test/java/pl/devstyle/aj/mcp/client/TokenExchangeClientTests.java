package pl.devstyle.aj.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.devstyle.aj.mcp.config.AjProperties;
import pl.devstyle.aj.mcp.exception.McpToolException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenExchangeClientTests {

    private static final String CLIENT_ID = "mcp client:id";
    private static final String CLIENT_SECRET = "s3cr&t:+%/=";
    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final List<String> forms = new CopyOnWriteArrayList<>();
    private HttpServer authorizationServer;
    private int responseStatus;
    private String responseBody;
    private TokenExchangeClient client;
    private String serverUrl;

    @BeforeEach
    void startAuthorizationServer() throws IOException {
        authorizationServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        authorizationServer.createContext("/oauth2/token", this::handleToken);
        authorizationServer.start();
        serverUrl = "http://localhost:" + authorizationServer.getAddress().getPort();
        client = new TokenExchangeClient(new AjProperties(
                new AjProperties.Backend(serverUrl),
                new AjProperties.Oauth(serverUrl, CLIENT_ID, CLIENT_SECRET),
                new AjProperties.Mcp("http://localhost:8181")));
    }

    @AfterEach
    void stopAuthorizationServer() {
        authorizationServer.stop(0);
    }

    @Test
    void exchange_sendsUrlEncodedBasicAndRfc8693Form() {
        respondWith(200, """
                {"access_token":"backend-token","issued_token_type":"%s","token_type":"Bearer",
                 "expires_in":300,"scope":"READ"}
                """.formatted(ACCESS_TOKEN_TYPE));

        var exchanged = client.exchange("subject-token");

        assertThat(exchanged).isEqualTo("backend-token");
        var expectedCredentials = URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8);
        assertThat(authorizationHeaders).containsExactly("Basic " + Base64.getEncoder()
                .encodeToString(expectedCredentials.getBytes(StandardCharsets.UTF_8)));
        assertThat(parseForm(forms.getFirst())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token", "subject-token",
                "subject_token_type", ACCESS_TOKEN_TYPE,
                "requested_token_type", ACCESS_TOKEN_TYPE,
                "resource", serverUrl + "/api"));
    }

    @Test
    void exchange_invalidScope_mapsToInsufficientPermissions() {
        respondWith(400, "{\"error\":\"invalid_scope\",\"error_description\":\"no backend scope\"}");

        assertThatThrownBy(() -> client.exchange("subject-token"))
                .isInstanceOfSatisfying(McpToolException.class, ex -> {
                    assertThat(ex.getErrorType()).isEqualTo(McpToolException.ErrorType.API_ERROR);
                    assertThat(ex.getMessage()).isEqualTo("Insufficient permissions");
                });
        assertThat(forms).hasSize(1);
    }

    @Test
    void exchange_unexpectedIssuedTokenType_mapsToBackendAuthorizationFailed() {
        respondWith(200, """
                {"access_token":"backend-token","issued_token_type":"urn:ietf:params:oauth:token-type:jwt",
                 "token_type":"Bearer","expires_in":300}
                """);

        assertThatThrownBy(() -> client.exchange("subject-token"))
                .isInstanceOfSatisfying(McpToolException.class, ex -> {
                    assertThat(ex.getErrorType()).isEqualTo(McpToolException.ErrorType.API_ERROR);
                    assertThat(ex.getMessage()).isEqualTo("Backend authorization failed");
                });
    }

    private void respondWith(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        forms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String form) {
        var fields = new HashMap<String, String>();
        for (var pair : form.split("&")) {
            var parts = pair.split("=", 2);
            fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return fields;
    }
}
