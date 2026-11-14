package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.SecurityMockMvcConfiguration;
import pl.devstyle.aj.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, SecurityMockMvcConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class OAuth2LoggingTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OAuth2Properties properties;

    private OAuth2TestFlows flows;

    @BeforeEach
    void setUp() {
        flows = new OAuth2TestFlows(mockMvc, objectMapper);
    }

    @Test
    void fullFlow_neverLogsTokensCodesOrSecrets(CapturedOutput output) throws Exception {
        var secrets = new ArrayList<String>();

        var sessionToken = flows.login("admin", "admin123");
        var client = flows.registerConfidentialClient();
        secrets.addAll(List.of(sessionToken, client.clientSecret()));

        var codeVerifier = OAuth2TestFlows.generateCodeVerifier();
        var authorizeResult = mockMvc.perform(post("/oauth2/authorize")
                        .param("_token", sessionToken)
                        .param("client_id", client.clientId())
                        .param("redirect_uri", OAuth2TestFlows.REDIRECT_URI)
                        .param("response_type", "code")
                        .param("scope", "mcp:read mcp:edit")
                        .param("state", "s")
                        .param("code_challenge", OAuth2TestFlows.codeChallenge(codeVerifier))
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andReturn();
        var code = new OAuth2TestFlows.AuthorizationCode(
                OAuth2TestFlows.queryParam(authorizeResult.getResponse().getHeader("Location"), "code"),
                codeVerifier);
        secrets.addAll(List.of(code.code(), code.codeVerifier()));

        var tokens = tokenResponse(flows.exchangeCodeRequest(client.clientId(), code)
                .param("client_secret", client.clientSecret()));
        var mcpToken = tokens.get("access_token").asText();
        var refreshToken = tokens.get("refresh_token").asText();
        secrets.addAll(List.of(mcpToken, refreshToken));

        mockMvc.perform(flows.exchangeCodeRequest(client.clientId(), code)
                        .param("client_secret", client.clientSecret()))
                .andExpect(status().isBadRequest());

        var refreshed = tokenResponse(flows.refreshRequest(client.clientId(), refreshToken)
                .param("client_secret", client.clientSecret()));
        secrets.addAll(List.of(refreshed.get("access_token").asText(), refreshed.get("refresh_token").asText()));

        mockMvc.perform(flows.refreshRequest(client.clientId(), refreshToken)
                        .param("client_secret", client.clientSecret()))
                .andExpect(status().isBadRequest());

        var mcpBasic = OAuth2TestFlows.basic(properties.mcpClientId(), properties.mcpClientSecret());
        var delegatedToken = flows.exchange(mcpToken, mcpBasic, properties.apiAudience())
                .get("access_token").asText();
        secrets.addAll(List.of(delegatedToken, properties.mcpClientSecret(),
                mcpBasic.substring("Basic ".length())));

        mockMvc.perform(flows.introspect(mcpToken, mcpBasic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        assertThat(output.getAll())
                .contains("OAuth2 token issued", "OAuth2 token refreshed", "OAuth2 token exchanged",
                        client.clientId());
        assertThat(secrets).doesNotContainNull().allSatisfy(secret -> assertThat(secret).isNotBlank());
        assertThat(secrets).allSatisfy(secret -> assertThat(output.getAll()).doesNotContain(secret));
    }

    @Test
    void tokenRejection_clientSuppliedValuesWithLineBreaks_logsSanitizedValues(CapturedOutput output)
            throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", "unknown")
                        .param("client_id", "evil\r\nFORGED-CLIENT-LINE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "password\nFORGED-GRANT-LINE"))
                .andExpect(status().isBadRequest());

        assertThat(output.getAll())
                .contains("client_id=evil__FORGED-CLIENT-LINE", "grant_type=password_FORGED-GRANT-LINE")
                .doesNotContain("\nFORGED-CLIENT-LINE", "\nFORGED-GRANT-LINE");
    }

    @Test
    void introspectionRejection_basicAuthenticatedClient_logsAuthenticatedClientId(CapturedOutput output)
            throws Exception {
        mockMvc.perform(post("/oauth2/introspect")
                        .header("Authorization",
                                OAuth2TestFlows.basic(properties.mcpClientId(), properties.mcpClientSecret())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        assertThat(output.getAll())
                .contains("OAuth2 introspection rejected | client_id=" + properties.mcpClientId() + " |")
                .doesNotContain("OAuth2 introspection rejected | client_id=null");
    }

    @Test
    void introspectionRejection_basicClientWithWrongSecret_logsClaimedClientIdWithoutSecret(CapturedOutput output)
            throws Exception {
        var wrongSecret = "wrong-secret-" + UUID.randomUUID();
        var basic = OAuth2TestFlows.basic(properties.mcpClientId(), wrongSecret);

        mockMvc.perform(flows.introspect("any-token", basic))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        assertThat(output.getAll())
                .contains("OAuth2 introspection rejected | client_id=" + properties.mcpClientId() + " | error=invalid_client")
                .doesNotContain(wrongSecret, basic.substring("Basic ".length()));
    }

    private JsonNode tokenResponse(MockHttpServletRequestBuilder request) throws Exception {
        var result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
