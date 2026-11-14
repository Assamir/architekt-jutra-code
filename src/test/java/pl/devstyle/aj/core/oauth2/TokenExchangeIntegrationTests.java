package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.SecurityMockMvcConfiguration;
import pl.devstyle.aj.TestcontainersConfiguration;
import pl.devstyle.aj.core.security.JwtTokenProvider;
import pl.devstyle.aj.core.security.OAuth2TokenClaims;
import pl.devstyle.aj.user.Permission;
import pl.devstyle.aj.user.User;
import pl.devstyle.aj.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, SecurityMockMvcConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class TokenExchangeIntegrationTests {

    private static final long DELEGATED_TOKEN_MAX_TTL_SECONDS = 300;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private OAuth2Properties properties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private OAuth2TestFlows flows;

    @BeforeEach
    void setUp() {
        flows = new OAuth2TestFlows(mockMvc, objectMapper);
    }

    @Test
    void exchange_mcpClientWithValidSubject_returnsDelegatedApiToken() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read mcp:edit");
        var subject = jwtTokenProvider.parseAccessToken(subjectToken).orElseThrow();

        var response = flows.exchange(subjectToken, mcpClientBasic(), properties.apiAudience());

        assertThat(response.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(response.get("issued_token_type").asText()).isEqualTo(OAuth2TestFlows.ACCESS_TOKEN_TYPE);
        assertThat(response.get("scope").asText()).isEqualTo("mcp:read mcp:edit");
        assertThat(response.get("expires_in").asLong()).isBetween(1L, DELEGATED_TOKEN_MAX_TTL_SECONDS);
        assertThat(response.has("refresh_token")).isFalse();

        var delegated = jwtTokenProvider.parseApiToken(response.get("access_token").asText()).orElseThrow();
        assertThat(delegated.audience()).containsExactly(properties.apiAudience());
        assertThat(delegated.subject()).isEqualTo("admin");
        assertThat(delegated.clientId()).isEqualTo(properties.mcpClientId());
        assertThat(delegated.actorSubject()).isEqualTo(properties.mcpClientId());
        assertThat(delegated.scopes()).containsExactlyInAnyOrder("mcp:read", "mcp:edit");
        assertThat(delegated.jti()).isNotBlank();
        assertThat(delegated.type()).isEqualTo("at+jwt");
        assertThat(delegated.expiresAt())
                .isBeforeOrEqualTo(subject.expiresAt())
                .isBeforeOrEqualTo(delegated.issuedAt().plusSeconds(DELEGATED_TOKEN_MAX_TTL_SECONDS));
    }

    @Test
    void exchange_delegatedToken_getProducts_returns200() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read mcp:edit");
        var delegatedToken = flows.exchange(subjectToken, mcpClientBasic(), properties.apiAudience())
                .get("access_token").asText();

        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + delegatedToken))
                .andExpect(status().isOk());
    }

    @Test
    void exchange_viewerSubject_isNotGrantedEditScope() throws Exception {
        var clientId = flows.registerPublicClient();
        var subjectToken = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims("viewer",
                Set.of("mcp:read", "mcp:edit"), properties.mcpResource(), clientId, null,
                Instant.now().plusSeconds(900)));

        var response = flows.exchange(subjectToken, mcpClientBasic(), properties.apiAudience());

        assertThat(response.get("scope").asText()).isEqualTo("mcp:read");
        var delegated = jwtTokenProvider.parseApiToken(response.get("access_token").asText()).orElseThrow();
        assertThat(delegated.scopes()).containsExactly("mcp:read");
    }

    @Test
    void exchange_viewerDelegatedToken_postProduct_returns403() throws Exception {
        var subjectToken = flows.obtainMcpToken("viewer", "viewer123", "mcp:read mcp:edit");
        var delegatedToken = flows.exchange(subjectToken, mcpClientBasic(), properties.apiAudience())
                .get("access_token").asText();

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + delegatedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exchange_permissionRevokedAfterConsent_returnsInvalidScope() throws Exception {
        var user = createAndSaveUser("exchange-revoked", "revoked123", Set.of(Permission.READ, Permission.EDIT));
        var subjectToken = flows.obtainMcpToken("exchange-revoked", "revoked123", "mcp:read mcp:edit");

        user.getPermissions().clear();
        userRepository.saveAndFlush(user);

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic(), properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void exchange_subjectExpiringBeforeCap_delegatedTokenExpiresWithSubject() throws Exception {
        var clientId = flows.registerPublicClient();
        var subjectExpiry = Instant.now().plusSeconds(60);
        var subjectToken = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims("admin",
                Set.of("mcp:read"), properties.mcpResource(), clientId, null, subjectExpiry));
        var subject = jwtTokenProvider.parseAccessToken(subjectToken).orElseThrow();

        var response = flows.exchange(subjectToken, mcpClientBasic(), properties.apiAudience());

        assertThat(response.get("expires_in").asLong()).isBetween(1L, 60L);
        var delegated = jwtTokenProvider.parseApiToken(response.get("access_token").asText()).orElseThrow();
        assertThat(delegated.expiresAt()).isEqualTo(subject.expiresAt());
    }

    @Test
    void exchange_subjectUserDeleted_returnsInvalidRequest() throws Exception {
        var user = createAndSaveUser("exchange-deleted", "deleted123", Set.of(Permission.READ));
        var subjectToken = flows.obtainMcpToken("exchange-deleted", "deleted123", "mcp:read");

        userRepository.delete(user);
        userRepository.flush();

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic(), properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    private String mcpClientBasic() {
        return OAuth2TestFlows.basic(properties.mcpClientId(), properties.mcpClientSecret());
    }

    private User createAndSaveUser(String username, String password, Set<Permission> permissions) {
        var user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPermissions(new HashSet<>(permissions));
        return userRepository.saveAndFlush(user);
    }
}
