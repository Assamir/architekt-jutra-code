package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.SecurityMockMvcConfiguration;
import pl.devstyle.aj.TestcontainersConfiguration;
import pl.devstyle.aj.core.security.JwtTokenProvider;
import pl.devstyle.aj.core.security.OAuth2TokenClaims;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, SecurityMockMvcConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class IntrospectionIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private OAuth2Properties properties;

    private OAuth2TestFlows flows;

    @BeforeEach
    void setUp() {
        flows = new OAuth2TestFlows(mockMvc, objectMapper);
    }

    @Test
    void introspect_activeMcpToken_returnsMetadataWithSpaceDelimitedScope() throws Exception {
        var mcpToken = flows.obtainMcpToken("admin", "admin123", "mcp:read mcp:edit");
        var parsed = jwtTokenProvider.parseAccessToken(mcpToken).orElseThrow();

        mockMvc.perform(flows.introspect(mcpToken, mcpClientBasic()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value("admin"))
                .andExpect(jsonPath("$.scope").value("mcp:read mcp:edit"))
                .andExpect(jsonPath("$.aud", contains(properties.mcpResource())))
                .andExpect(jsonPath("$.client_id").value(parsed.clientId()))
                .andExpect(jsonPath("$.iss").value(properties.issuer()))
                .andExpect(jsonPath("$.iat").value(parsed.issuedAt().getEpochSecond()))
                .andExpect(jsonPath("$.exp").value(parsed.expiresAt().getEpochSecond()))
                .andExpect(jsonPath("$.jti").value(parsed.jti()))
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void introspect_withoutClientAuthentication_returns401() throws Exception {
        var mcpToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.introspect(mcpToken, null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", notNullValue()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void introspect_missingToken_returns400InvalidRequest() throws Exception {
        mockMvc.perform(post("/oauth2/introspect").header("Authorization", mcpClientBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.active").doesNotExist());
    }

    @Test
    void introspect_sessionToken_returnsInactive() throws Exception {
        var sessionToken = flows.login("admin", "admin123");

        expectInactive(mockMvc.perform(flows.introspect(sessionToken, mcpClientBasic())));
    }

    @Test
    void introspect_delegatedApiToken_returnsInactive() throws Exception {
        var mcpToken = flows.obtainMcpToken("admin", "admin123", "mcp:read mcp:edit");
        var delegatedToken = flows.exchange(mcpToken, mcpClientBasic(), properties.apiAudience())
                .get("access_token").asText();

        expectInactive(mockMvc.perform(flows.introspect(delegatedToken, mcpClientBasic())));
    }

    @Test
    void introspect_expiredToken_returnsInactive() throws Exception {
        var clientId = flows.registerPublicClient();
        var expiredToken = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims("admin",
                Set.of("mcp:read"), properties.mcpResource(), clientId, null,
                Instant.now().minusSeconds(60)));

        expectInactive(mockMvc.perform(flows.introspect(expiredToken, mcpClientBasic())));
    }

    @Test
    void introspect_garbageToken_returnsInactive() throws Exception {
        expectInactive(mockMvc.perform(flows.introspect("not-a-jwt", mcpClientBasic())));
    }

    @Test
    void introspect_unboundConfidentialClient_returnsInactive() throws Exception {
        var mcpToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");
        var client = flows.registerConfidentialClient();

        expectInactive(mockMvc.perform(flows.introspect(mcpToken, null)
                .param("client_id", client.clientId())
                .param("client_secret", client.clientSecret())));
    }

    private static void expectInactive(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.scope").doesNotExist());
    }

    private String mcpClientBasic() {
        return OAuth2TestFlows.basic(properties.mcpClientId(), properties.mcpClientSecret());
    }
}
