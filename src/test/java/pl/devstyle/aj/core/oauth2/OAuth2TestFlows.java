package pl.devstyle.aj.core.oauth2;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pl.devstyle.aj.api.LoginRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc helpers for multi-step OAuth2 flows shared by the OAuth2 test classes.
 */
class OAuth2TestFlows {

    static final String REDIRECT_URI = "http://localhost:3000/callback";
    static final String TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    OAuth2TestFlows(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    record RegisteredTestClient(String clientId, String clientSecret) {
    }

    record AuthorizationCode(String code, String codeVerifier) {
    }

    String login(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    String registerPublicClient() throws Exception {
        return register("none", REDIRECT_URI, "authorization_code", "refresh_token").clientId();
    }

    RegisteredTestClient registerConfidentialClient() throws Exception {
        return register("client_secret_post", REDIRECT_URI, "authorization_code", "refresh_token");
    }

    MockHttpServletRequestBuilder authorizeRequest(String sessionToken, String clientId, String scope,
                                                   String codeChallenge, String... resources) {
        var request = post("/oauth2/authorize")
                .header("Authorization", "Bearer " + sessionToken)
                .param("client_id", clientId)
                .param("redirect_uri", REDIRECT_URI)
                .param("response_type", "code")
                .param("state", "s")
                .param("code_challenge", codeChallenge)
                .param("code_challenge_method", "S256");
        if (scope != null) {
            request.param("scope", scope);
        }
        if (resources.length > 0) {
            request.param("resource", resources);
        }
        return request;
    }

    AuthorizationCode authorize(String sessionToken, String clientId, String scope, String... resources)
            throws Exception {
        var codeVerifier = generateCodeVerifier();
        var result = mockMvc.perform(authorizeRequest(sessionToken, clientId, scope, codeChallenge(codeVerifier),
                        resources))
                .andExpect(status().isFound())
                .andReturn();
        var code = queryParam(result.getResponse().getHeader("Location"), "code");
        return new AuthorizationCode(code, codeVerifier);
    }

    MockHttpServletRequestBuilder exchangeCodeRequest(String clientId, AuthorizationCode authorizationCode,
                                                      String... resources) {
        var request = post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", authorizationCode.code())
                .param("redirect_uri", REDIRECT_URI)
                .param("client_id", clientId)
                .param("code_verifier", authorizationCode.codeVerifier());
        if (resources.length > 0) {
            request.param("resource", resources);
        }
        return request;
    }

    JsonNode exchangeCode(String clientId, AuthorizationCode authorizationCode) throws Exception {
        var result = mockMvc.perform(exchangeCodeRequest(clientId, authorizationCode))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Builds a refresh-token grant request. A null {@code clientId} omits the {@code client_id} parameter.
     */
    MockHttpServletRequestBuilder refreshRequest(String clientId, String refreshToken, String... resources) {
        var request = post("/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken);
        if (clientId != null) {
            request.param("client_id", clientId);
        }
        if (resources.length > 0) {
            request.param("resource", resources);
        }
        return request;
    }

    JsonNode refresh(String clientId, String refreshToken) throws Exception {
        var result = mockMvc.perform(refreshRequest(clientId, refreshToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Runs the full public-client flow (login, registration, consent, code exchange) and returns the T1 access token.
     */
    String obtainMcpToken(String username, String password, String scope) throws Exception {
        var sessionToken = login(username, password);
        var clientId = registerPublicClient();
        var code = authorize(sessionToken, clientId, scope);
        return exchangeCode(clientId, code).get("access_token").asText();
    }

    /**
     * Builds a token-exchange request. A null {@code basicHeader} sends no client authentication.
     */
    MockHttpServletRequestBuilder exchangeRequest(String subjectToken, String basicHeader, String... resources) {
        var request = post("/oauth2/token")
                .param("grant_type", TOKEN_EXCHANGE_GRANT)
                .param("subject_token", subjectToken)
                .param("subject_token_type", ACCESS_TOKEN_TYPE);
        if (basicHeader != null) {
            request.header("Authorization", basicHeader);
        }
        if (resources.length > 0) {
            request.param("resource", resources);
        }
        return request;
    }

    JsonNode exchange(String subjectToken, String basicHeader, String resource) throws Exception {
        var result = mockMvc.perform(exchangeRequest(subjectToken, basicHeader, resource))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Builds an RFC 7662 introspection request. A null {@code basicHeader} sends no client authentication.
     */
    MockHttpServletRequestBuilder introspect(String token, String basicHeader) {
        var request = post("/oauth2/introspect")
                .param("token", token);
        if (basicHeader != null) {
            request.header("Authorization", basicHeader);
        }
        return request;
    }

    static String basic(String clientId, String clientSecret) {
        var credentials = URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    static String generateCodeVerifier() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String codeChallenge(String verifier) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    static String queryParam(String url, String name) {
        assertThat(url).isNotNull();
        return Arrays.stream(URI.create(url).getQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(pair -> pair[0].equals(name))
                .map(pair -> pair[1])
                .findFirst()
                .orElseThrow();
    }

    /**
     * Registers a client through DCR with the given redirect URI and grant types.
     */
    RegisteredTestClient register(String tokenEndpointAuthMethod, String redirectUri, String... grantTypes)
            throws Exception {
        var grantTypesJson = Arrays.stream(grantTypes)
                .map(grantType -> "\"" + grantType + "\"")
                .collect(Collectors.joining(", "));
        var body = """
                {
                    "client_name": "OAuth2 Test Client",
                    "grant_types": [%s],
                    "redirect_uris": ["%s"],
                    "response_types": ["code"],
                    "token_endpoint_auth_method": "%s",
                    "scope": "mcp:read mcp:edit"
                }
                """.formatted(grantTypesJson, redirectUri, tokenEndpointAuthMethod);
        var result = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        var registration = objectMapper.readTree(result.getResponse().getContentAsString());
        return new RegisteredTestClient(registration.get("client_id").asText(),
                registration.path("client_secret").asText());
    }
}
