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

import java.time.Instant;
import java.util.Set;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, SecurityMockMvcConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class TokenExchangeValidationTests {

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
    void exchange_withoutClientAuthentication_returns401WithBasicChallenge() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.exchangeRequest(subjectToken, null, properties.apiAudience()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")))
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void exchange_confidentialDcrClient_returnsUnauthorizedClient() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");
        var dcrClient = flows.registerConfidentialClient();

        mockMvc.perform(flows.exchangeRequest(subjectToken, null, properties.apiAudience())
                        .param("client_id", dcrClient.clientId())
                        .param("client_secret", dcrClient.clientSecret()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"));
    }

    @Test
    void exchange_sessionTokenAsSubject_returnsInvalidRequest() throws Exception {
        var sessionToken = flows.login("admin", "admin123");

        mockMvc.perform(flows.exchangeRequest(sessionToken, mcpClientBasic(), properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void exchange_delegatedTokenAsSubject_returnsInvalidRequest() throws Exception {
        var delegatedSubject = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims("admin",
                Set.of("mcp:read"), properties.mcpResource(), properties.mcpClientId(), properties.mcpClientId(),
                Instant.now().plusSeconds(300)));

        mockMvc.perform(flows.exchangeRequest(delegatedSubject, mcpClientBasic(), properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void exchange_resourceNotApiAudience_returnsInvalidTarget() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic(), properties.mcpResource()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void exchange_missingResource_returnsInvalidTarget() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void exchange_basicAndFormClientSecret_returnsInvalidRequest() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic(), properties.apiAudience())
                        .param("client_secret", properties.mcpClientSecret()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void exchange_unsupportedRequestedTokenType_returnsInvalidRequest() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic(), properties.apiAudience())
                        .param("requested_token_type", "urn:ietf:params:oauth:token-type:refresh_token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void exchange_actorTokenPresent_returnsInvalidRequest() throws Exception {
        var subjectToken = flows.obtainMcpToken("admin", "admin123", "mcp:read");

        mockMvc.perform(flows.exchangeRequest(subjectToken, mcpClientBasic(), properties.apiAudience())
                        .param("actor_token", subjectToken)
                        .param("actor_token_type", OAuth2TestFlows.ACCESS_TOKEN_TYPE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void exchange_accessTokenSubjectWithForeignAudience_returnsInvalidRequest() throws Exception {
        var clientId = flows.registerPublicClient();
        var foreignSubject = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims("admin",
                Set.of("mcp:read"), "http://other-resource.example", clientId, null, Instant.now().plusSeconds(900)));

        mockMvc.perform(flows.exchangeRequest(foreignSubject, mcpClientBasic(), properties.apiAudience()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    private String mcpClientBasic() {
        return OAuth2TestFlows.basic(properties.mcpClientId(), properties.mcpClientSecret());
    }
}
