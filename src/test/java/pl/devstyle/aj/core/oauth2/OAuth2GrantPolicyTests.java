package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.SecurityMockMvcConfiguration;
import pl.devstyle.aj.TestcontainersConfiguration;
import pl.devstyle.aj.core.security.JwtTokenProvider;
import pl.devstyle.aj.core.security.OAuth2TokenClaims;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, SecurityMockMvcConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class OAuth2GrantPolicyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private OAuth2Properties properties;

    @Autowired
    private RefreshTokenService refreshTokenService;

    private OAuth2TestFlows flows;

    @BeforeEach
    void setUp() {
        flows = new OAuth2TestFlows(mockMvc, objectMapper);
    }

    @Test
    void authorize_withoutResource_issuesTokenForMcpAudience() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.registerPublicClient();

        var code = flows.authorize(sessionToken, clientId, "mcp:read");
        var tokenResponse = flows.exchangeCode(clientId, code);

        assertThat(accessTokenAudience(tokenResponse.get("access_token").asText()))
                .containsExactly(properties.mcpResource());
    }

    @Test
    void authorize_trailingSlashResource_issuesCanonicalAudience() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.registerPublicClient();

        var code = flows.authorize(sessionToken, clientId, "mcp:read", "http://localhost:8181/");
        var tokenResponse = flows.exchangeCode(clientId, code);

        assertThat(accessTokenAudience(tokenResponse.get("access_token").asText()))
                .containsExactly("http://localhost:8181");
    }

    @Test
    void authorize_apiResource_returnsInvalidTarget() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.registerPublicClient();
        var challenge = OAuth2TestFlows.codeChallenge(OAuth2TestFlows.generateCodeVerifier());

        mockMvc.perform(flows.authorizeRequest(sessionToken, clientId, "mcp:read", challenge,
                        properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void authorize_multipleResourceParams_returnsInvalidTarget() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.registerPublicClient();
        var challenge = OAuth2TestFlows.codeChallenge(OAuth2TestFlows.generateCodeVerifier());

        mockMvc.perform(flows.authorizeRequest(sessionToken, clientId, "mcp:read", challenge,
                        properties.mcpResource(), properties.mcpResource()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void authorize_delegatedTokenAsSession_returns403AccessDenied() throws Exception {
        var clientId = flows.registerPublicClient();
        var challenge = OAuth2TestFlows.codeChallenge(OAuth2TestFlows.generateCodeVerifier());
        var delegatedToken = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims(
                "admin", Set.of("mcp:read", "mcp:edit"), properties.apiAudience(), properties.mcpClientId(),
                properties.mcpClientId(), Instant.now().plusSeconds(300)));

        mockMvc.perform(post("/oauth2/authorize")
                        .param("_token", delegatedToken)
                        .param("client_id", clientId)
                        .param("redirect_uri", OAuth2TestFlows.REDIRECT_URI)
                        .param("response_type", "code")
                        .param("scope", "mcp:read")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    @Test
    void authorizationCode_differentClientId_returnsInvalidGrant() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var ownerClientId = flows.registerPublicClient();
        var otherClientId = flows.registerPublicClient();
        var code = flows.authorize(sessionToken, ownerClientId, "mcp:read");

        mockMvc.perform(flows.exchangeCodeRequest(otherClientId, code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void authorizationCode_apiResource_returnsInvalidTarget() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.registerPublicClient();
        var code = flows.authorize(sessionToken, clientId, "mcp:read");

        mockMvc.perform(flows.exchangeCodeRequest(clientId, code, properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void refresh_apiResource_returnsInvalidTarget() throws Exception {
        var clientId = flows.registerPublicClient();
        var refreshToken = obtainRefreshToken(clientId);

        mockMvc.perform(flows.refreshRequest(clientId, refreshToken, properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void refresh_byDifferentClient_returnsInvalidGrantAndKeepsToken() throws Exception {
        var ownerClientId = flows.registerPublicClient();
        var otherClientId = flows.registerPublicClient();
        var refreshToken = obtainRefreshToken(ownerClientId);

        mockMvc.perform(flows.refreshRequest(otherClientId, refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        var ownerRefresh = flows.refresh(ownerClientId, refreshToken);
        assertThat(ownerRefresh.get("access_token").asText()).isNotBlank();
        assertThat(ownerRefresh.get("refresh_token").asText()).isNotEqualTo(refreshToken);
    }

    @Test
    void refresh_publicClientWithoutClientId_returnsInvalidRequest() throws Exception {
        var clientId = flows.registerPublicClient();
        var refreshToken = obtainRefreshToken(clientId);

        mockMvc.perform(flows.refreshRequest(null, refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void refresh_unknownPublicClient_returns401InvalidClientWithBasicChallenge() throws Exception {
        var clientId = flows.registerPublicClient();
        var refreshToken = obtainRefreshToken(clientId);

        mockMvc.perform(flows.refreshRequest("unknown-client", refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")))
                .andExpect(jsonPath("$.error").value("invalid_client"));

        assertThat(flows.refresh(clientId, refreshToken).get("access_token").asText()).isNotBlank();
    }

    @Test
    void token_unknownGrantType_returnsUnsupportedGrantType() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "password")
                        .param("username", "admin")
                        .param("password", "admin123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void authorize_mcpServerClient_returnsUnauthorizedClient() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var challenge = OAuth2TestFlows.codeChallenge(OAuth2TestFlows.generateCodeVerifier());

        mockMvc.perform(flows.authorizeRequest(sessionToken, properties.mcpClientId(), "mcp:read", challenge))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void exchangeCode_clientWithoutRefreshTokenGrant_omitsRefreshToken() throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.register("none", OAuth2TestFlows.REDIRECT_URI, "authorization_code").clientId();
        var code = flows.authorize(sessionToken, clientId, "mcp:read");

        var tokenResponse = flows.exchangeCode(clientId, code);

        assertThat(tokenResponse.get("access_token").asText()).isNotBlank();
        assertThat(tokenResponse.has("refresh_token")).isFalse();
    }

    @Test
    void refresh_clientWithoutRefreshTokenGrant_returnsUnauthorizedClient() throws Exception {
        var clientId = flows.register("none", OAuth2TestFlows.REDIRECT_URI, "authorization_code").clientId();
        var refreshToken = refreshTokenService.issueToken("admin", "mcp:read", clientId, properties.mcpResource());

        mockMvc.perform(flows.refreshRequest(clientId, refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void authorize_stateWithReservedCharactersAndRedirectWithQuery_encodesLocation() throws Exception {
        var redirectUri = "http://localhost:3000/callback?tenant=a%20b";
        var state = "x&code=evil=1 +/?";
        var sessionToken = flows.login("admin", "admin123");
        var clientId = flows.register("none", redirectUri, "authorization_code", "refresh_token").clientId();
        var challenge = OAuth2TestFlows.codeChallenge(OAuth2TestFlows.generateCodeVerifier());

        var location = mockMvc.perform(post("/oauth2/authorize")
                        .header("Authorization", "Bearer " + sessionToken)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("response_type", "code")
                        .param("state", state)
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");

        assertThat(location)
                .startsWith("http://localhost:3000/callback?tenant=a%20b&code=")
                .endsWith("&state=x%26code%3Devil%3D1%20%2B%2F%3F");
        var params = Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0],
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertThat(params).containsOnlyKeys("tenant", "code", "state")
                .containsEntry("tenant", "a b")
                .containsEntry("state", state);
        assertThat(params.get("code")).isNotBlank();
    }

    private String obtainRefreshToken(String clientId) throws Exception {
        var sessionToken = flows.login("admin", "admin123");
        var code = flows.authorize(sessionToken, clientId, "mcp:read");
        return flows.exchangeCode(clientId, code).get("refresh_token").asText();
    }

    private Set<String> accessTokenAudience(String accessToken) {
        return jwtTokenProvider.parseAccessToken(accessToken).orElseThrow().audience();
    }
}
