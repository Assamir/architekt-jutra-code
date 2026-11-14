package pl.devstyle.aj.core.security;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.TestcontainersConfiguration;
import pl.devstyle.aj.api.LoginRequest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private static final String ISSUER = "http://localhost:8180";
    private static final String API_AUDIENCE = ISSUER + "/api";

    private String loginAndGetToken(String username, String password) throws Exception {
        var request = new LoginRequest(username, password);
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    @Test
    void login_validCredentials_returnsTokenInResponse() throws Exception {
        var request = new LoginRequest("admin", "admin123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(notNullValue()));
    }

    @Test
    void login_invalidPassword_returns401() throws Exception {
        var request = new LoginRequest("admin", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_unknownUser_returns401() throws Exception {
        var request = new LoginRequest("nonexistent", "password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_validToken_returns200() throws Exception {
        var token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_expiredToken_returns401() throws Exception {
        // Generate an expired token directly via the provider
        var expiredToken = jwtTokenProvider.generateTokenWithExpiration("admin",
                java.util.Set.of("READ", "EDIT", "PLUGIN_MANAGEMENT"), -1000L);

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void editEndpoint_readOnlyUser_returns403() throws Exception {
        var token = loginAndGetToken("viewer", "viewer123");

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"description\":\"Test\",\"price\":10.0,\"sku\":\"TST-001\",\"categoryId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void pluginManagement_nonAdminUser_returns403() throws Exception {
        var token = loginAndGetToken("editor", "editor123");

        mockMvc.perform(put("/api/plugins/test-plugin/manifest")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Plugin\",\"version\":\"1.0.0\",\"url\":\"http://localhost:3000\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // --- Gap-filling tests (Group 6) ---

    @Test
    void protectedEndpoint_malformedJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer not-a-valid-jwt-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_jwtSignedWithWrongKey_returns401() throws Exception {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "dGhpc0lzQVdyb25nU2VjcmV0S2V5Rm9yVGVzdGluZ1B1cnBvc2VzT25seQ==".getBytes());
        var wrongKeyToken = Jwts.builder()
                .subject("admin")
                .claim("permissions", java.util.List.of("READ", "EDIT", "PLUGIN_MANAGEMENT"))
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600000))
                .signWith(wrongKey)
                .compact();

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + wrongKeyToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void healthEndpoint_noToken_returns200() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void loginEndpoint_noToken_isAccessible() throws Exception {
        var request = new LoginRequest("admin", "admin123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void readEndpoint_viewerUser_returns200() throws Exception {
        var token = loginAndGetToken("viewer", "viewer123");

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // --- Fixed issuer and API audience (T0) ---

    @Test
    void login_validCredentials_tokenCarriesIssuerAndApiAudience() throws Exception {
        var token = loginAndGetToken("admin", "admin123");

        var payload = objectMapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(payload.get("aud").isArray()).isTrue();
        assertThat(payload.get("aud").valueStream().map(node -> node.asText()).toList()).contains(API_AUDIENCE);
    }

    @Test
    void protectedEndpoint_tokenWithoutAudience_returns401() throws Exception {
        var token = signedSessionToken(ISSUER, null);

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_tokenWithForeignIssuer_returns401() throws Exception {
        var token = signedSessionToken("http://attacker.example", API_AUDIENCE);

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_tokenWithForeignAudience_returns401() throws Exception {
        var token = signedSessionToken(ISSUER, "http://localhost:8181");

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_bearerPrefixMissing_returns401() throws Exception {
        var token = loginAndGetToken("admin", "admin123");

        mockMvc.perform(get("/api/products")
                        .header("Authorization", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    private String signedSessionToken(String issuer, String audience) {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret));
        var builder = Jwts.builder()
                .issuer(issuer)
                .subject("admin")
                .claim("permissions", java.util.List.of("READ", "EDIT", "PLUGIN_MANAGEMENT"))
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600000));
        if (audience != null) {
            builder.audience().add(audience);
        }
        return builder.signWith(key).compact();
    }
}
