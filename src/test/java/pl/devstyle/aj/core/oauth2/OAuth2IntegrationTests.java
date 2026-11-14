package pl.devstyle.aj.core.oauth2;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.TestcontainersConfiguration;
import pl.devstyle.aj.api.LoginRequest;
import pl.devstyle.aj.core.security.JwtTokenProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class OAuth2IntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String loginAndGetToken(String username, String password) throws Exception {
        var request = new LoginRequest(username, password);
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String registerClient(String redirectUri) throws Exception {
        var body = """
                {
                    "client_name": "Test MCP Client",
                    "grant_types": ["authorization_code", "refresh_token"],
                    "redirect_uris": ["%s"],
                    "response_types": ["code"],
                    "token_endpoint_auth_method": "none",
                    "scope": "mcp:read mcp:edit"
                }
                """.formatted(redirectUri);

        var result = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return result.getResponse().getContentAsString();
    }

    private String generateCodeVerifier() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    @Test
    void getMetadata_returnsConfiguredIssuerIntrospectionAndExchangeGrant() throws Exception {
        var issuer = "http://localhost:8180";

        mockMvc.perform(get("/.well-known/oauth-authorization-server")
                        .header("X-Forwarded-Host", "attacker.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(issuer))
                .andExpect(jsonPath("$.authorization_endpoint").value(issuer + "/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value(issuer + "/oauth2/token"))
                .andExpect(jsonPath("$.registration_endpoint").value(issuer + "/oauth2/register"))
                .andExpect(jsonPath("$.introspection_endpoint").value(issuer + "/oauth2/introspect"))
                .andExpect(jsonPath("$.introspection_endpoint_auth_methods_supported",
                        contains("client_secret_basic", "client_secret_post")))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported",
                        contains("client_secret_post", "client_secret_basic", "none")))
                .andExpect(jsonPath("$.grant_types_supported", contains("authorization_code", "refresh_token",
                        "urn:ietf:params:oauth:grant-type:token-exchange")))
                .andExpect(jsonPath("$.response_types_supported", contains("code")))
                .andExpect(jsonPath("$.code_challenge_methods_supported", contains("S256")))
                .andExpect(jsonPath("$.scopes_supported", contains("mcp:read", "mcp:edit")));
    }

    @Test
    void registerClient_returns201WithClientCredentials() throws Exception {
        var body = """
                {
                    "client_name": "My MCP Client",
                    "grant_types": ["authorization_code"],
                    "redirect_uris": ["http://localhost:3000/callback"],
                    "response_types": ["code"],
                    "token_endpoint_auth_method": "client_secret_post",
                    "scope": "mcp:read mcp:edit"
                }
                """;

        mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").value(notNullValue()))
                .andExpect(jsonPath("$.client_secret").value(notNullValue()))
                .andExpect(jsonPath("$.client_name").value("My MCP Client"));
    }

    @Test
    void registerClient_withInvalidName_returns400() throws Exception {
        var body = """
                {
                    "grant_types": ["authorization_code"],
                    "redirect_uris": ["http://localhost:3000/callback"],
                    "token_endpoint_auth_method": "none"
                }
                """;

        mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000/cb?x=a b",
            "http://localhost:3000/{cb}",
            "https://client.example/cb?x=ä",
            "https://client.example:port/cb"
    })
    void registerClient_redirectUriWithIllegalCharacters_returns400(String redirectUri) throws Exception {
        postRegistration("""
                {
                    "client_name": "Bad Redirect Client",
                    "grant_types": ["authorization_code"],
                    "redirect_uris": ["%s"],
                    "token_endpoint_auth_method": "none"
                }
                """.formatted(redirectUri))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost.attacker.example/cb",
            "http://127.0.0.1.nip.io/cb",
            "http://localhost@attacker.example/cb",
            "http://[::1].attacker.example/cb",
            "http:/localhost/cb",
            "ftp://localhost/cb"
    })
    void registerClient_loopbackPrefixHost_returns400(String redirectUri) throws Exception {
        postRegistration("""
                {
                    "client_name": "Prefix Host Client",
                    "grant_types": ["authorization_code"],
                    "redirect_uris": ["%s"],
                    "token_endpoint_auth_method": "none"
                }
                """.formatted(redirectUri))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000/callback",
            "http://127.0.0.1/callback?tenant=a%20b",
            "http://[::1]:6274/oauth/callback",
            "https://client.example/callback"
    })
    void registerClient_loopbackOrHttpsRedirectUri_returns201(String redirectUri) throws Exception {
        postRegistration("""
                {
                    "client_name": "Valid Redirect Client",
                    "grant_types": ["authorization_code"],
                    "redirect_uris": ["%s"],
                    "token_endpoint_auth_method": "none"
                }
                """.formatted(redirectUri))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.redirect_uris", contains(redirectUri)));
    }

    @Test
    void registerClient_withoutGrantTypes_returns201WithAuthorizationCodeDefault() throws Exception {
        postRegistration("""
                {
                    "client_name": "Default Grant Client",
                    "redirect_uris": ["http://localhost:3000/callback"],
                    "token_endpoint_auth_method": "none"
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").value(notNullValue()))
                .andExpect(jsonPath("$.grant_types", contains("authorization_code")));
    }

    @Test
    void registerClient_withoutRedirectUris_returns400() throws Exception {
        postRegistration("""
                {
                    "client_name": "No Redirect Client",
                    "token_endpoint_auth_method": "none"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    @Test
    void registerClient_refreshOnlyWithoutRedirectUris_returns400() throws Exception {
        postRegistration("""
                {
                    "client_name": "Refresh Only Client",
                    "grant_types": ["refresh_token"],
                    "token_endpoint_auth_method": "none"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
    }

    private ResultActions postRegistration(String body) throws Exception {
        return mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void authorizationCodeFlow_fullCycle() throws Exception {
        var token = loginAndGetToken("admin", "admin123");
        var redirectUri = "http://localhost:3000/callback";

        // Register client
        var registrationJson = registerClient(redirectUri);
        var registration = objectMapper.readTree(registrationJson);
        var clientId = registration.get("client_id").asText();

        // Generate PKCE
        var codeVerifier = generateCodeVerifier();
        var codeChallenge = generateCodeChallenge(codeVerifier);

        // Authorize
        var authorizeResult = mockMvc.perform(post("/oauth2/authorize")
                        .header("Authorization", "Bearer " + token)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("response_type", "code")
                        .param("scope", "mcp:read mcp:edit")
                        .param("state", "test-state")
                        .param("code_challenge", codeChallenge)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();

        var location = authorizeResult.getResponse().getHeader("Location");
        assertThat(location).startsWith(redirectUri).contains("state=test-state");
        var code = extractQueryParam(location, "code");

        // Token exchange
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("client_id", clientId)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").value(notNullValue()))
                .andExpect(jsonPath("$.refresh_token").value(notNullValue()))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andExpect(jsonPath("$.scope").value("mcp:read mcp:edit"))
                .andExpect(jsonPath("$.issued_token_type").doesNotExist());
    }

    @Test
    void authorize_withPkce_requiresCodeVerifierOnTokenExchange() throws Exception {
        var token = loginAndGetToken("admin", "admin123");
        var redirectUri = "http://localhost:3000/callback";

        var registrationJson = registerClient(redirectUri);
        var registration = objectMapper.readTree(registrationJson);
        var clientId = registration.get("client_id").asText();

        var codeVerifier = generateCodeVerifier();
        var codeChallenge = generateCodeChallenge(codeVerifier);

        // Authorize with PKCE
        var authorizeResult = mockMvc.perform(post("/oauth2/authorize")
                        .header("Authorization", "Bearer " + token)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("response_type", "code")
                        .param("code_challenge", codeChallenge)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();

        var code = extractQueryParam(authorizeResult.getResponse().getHeader("Location"), "code");

        // Token exchange without code_verifier should fail
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("client_id", clientId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void authorize_withInvalidRedirectUri_returns400() throws Exception {
        var token = loginAndGetToken("admin", "admin123");
        var redirectUri = "http://localhost:3000/callback";

        var registrationJson = registerClient(redirectUri);
        var registration = objectMapper.readTree(registrationJson);
        var clientId = registration.get("client_id").asText();

        mockMvc.perform(post("/oauth2/authorize")
                        .header("Authorization", "Bearer " + token)
                        .param("client_id", clientId)
                        .param("redirect_uri", "http://evil.com/callback")
                        .param("response_type", "code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void refreshToken_sameClient_returnsNewAccessToken() throws Exception {
        var token = loginAndGetToken("admin", "admin123");
        var redirectUri = "http://localhost:3000/callback";

        var registrationJson = registerClient(redirectUri);
        var registration = objectMapper.readTree(registrationJson);
        var clientId = registration.get("client_id").asText();

        var codeVerifier = generateCodeVerifier();
        var codeChallenge = generateCodeChallenge(codeVerifier);

        // Authorize
        var authorizeResult = mockMvc.perform(post("/oauth2/authorize")
                        .header("Authorization", "Bearer " + token)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("response_type", "code")
                        .param("code_challenge", codeChallenge)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();

        var code = extractQueryParam(authorizeResult.getResponse().getHeader("Location"), "code");

        // Token exchange
        var tokenResult = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("client_id", clientId)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn();

        var tokenJson = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        var refreshToken = tokenJson.get("refresh_token").asText();

        // Refresh token exchange by the client the token was issued to
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").value(notNullValue()))
                .andExpect(jsonPath("$.refresh_token").value(not(refreshToken)))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900));
    }

    @Test
    void authorizationCode_invalidCode_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", "invalid-code")
                        .param("redirect_uri", "http://localhost:3000/callback")
                        .param("client_id", "nonexistent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    private String extractQueryParam(String url, String paramName) {
        var queryStart = url.indexOf('?');
        if (queryStart == -1) return null;
        var query = url.substring(queryStart + 1);
        for (var param : query.split("&")) {
            var parts = param.split("=", 2);
            if (parts[0].equals(paramName)) {
                return parts.length > 1 ? parts[1] : "";
            }
        }
        return null;
    }
}
