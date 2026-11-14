package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.TestcontainersConfiguration;
import pl.devstyle.aj.api.LoginRequest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token-boundary tests: an OAuth access token issued for the MCP server (T1) must not be usable at the host API,
 * and the scopes it carries must never exceed the user's permissions.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class McpTokenBoundaryTests {

    private static final String REDIRECT_URI = "http://localhost:3000/callback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void mcpAccessToken_replayedAtApi_returns401() throws Exception {
        var tokenResponse = obtainMcpTokenResponse("admin", "admin123", "mcp:read mcp:edit");
        var mcpAccessToken = tokenResponse.get("access_token").asText();

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + mcpAccessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorize_viewerRequestingMcpEdit_isNotGrantedEditScope() throws Exception {
        var tokenResponse = obtainMcpTokenResponse("viewer", "viewer123", "mcp:read mcp:edit");

        assertThat(tokenResponse.has("scope")).isTrue();
        var grantedScopes = Arrays.asList(tokenResponse.get("scope").asText().split(" "));
        assertThat(grantedScopes).contains("mcp:read").doesNotContain("mcp:edit");
    }

    private tools.jackson.databind.JsonNode obtainMcpTokenResponse(String username, String password, String scope)
            throws Exception {
        var sessionToken = login(username, password);
        var clientId = registerPublicClient();
        var codeVerifier = generateCodeVerifier();

        var authorizeResult = mockMvc.perform(post("/oauth2/authorize")
                        .header("Authorization", "Bearer " + sessionToken)
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("response_type", "code")
                        .param("scope", scope)
                        .param("state", "s")
                        .param("code_challenge", codeChallenge(codeVerifier))
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();
        var code = queryParam(authorizeResult.getResponse().getHeader("Location"), "code");

        var tokenResult = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", clientId)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(tokenResult.getResponse().getContentAsString());
    }

    private String login(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String registerPublicClient() throws Exception {
        var body = """
                {
                    "client_name": "Boundary Test Client",
                    "grant_types": ["authorization_code", "refresh_token"],
                    "redirect_uris": ["%s"],
                    "response_types": ["code"],
                    "token_endpoint_auth_method": "none",
                    "scope": "mcp:read mcp:edit"
                }
                """.formatted(REDIRECT_URI);
        var result = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("client_id").asText();
    }

    private static String generateCodeVerifier() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String verifier) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String queryParam(String url, String name) {
        assertThat(url).isNotNull();
        return Arrays.stream(URI.create(url).getQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(pair -> pair[0].equals(name))
                .map(pair -> pair[1])
                .findFirst()
                .orElseThrow();
    }
}
