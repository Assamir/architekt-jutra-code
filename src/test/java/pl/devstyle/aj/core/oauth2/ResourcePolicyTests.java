package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourcePolicyTests {

    private static final String MCP_RESOURCE = "http://localhost:8181";
    private static final String API_AUDIENCE = "http://localhost:8180/api";

    private final ResourcePolicy policy = new ResourcePolicy(
            new OAuth2Properties("http://localhost:8180", MCP_RESOURCE, "aj-mcp-server", "secret"));

    @Test
    void resolveForAuthorization_missing_defaultsToMcpResource() {
        assertThat(policy.resolveForAuthorization(null, "client-1")).isEqualTo(MCP_RESOURCE);
        assertThat(policy.resolveForAuthorization(" ", "client-1")).isEqualTo(MCP_RESOURCE);
    }

    @Test
    void resolveForAuthorization_trailingSlash_returnsCanonical() {
        assertThat(policy.resolveForAuthorization("http://localhost:8181/", "client-1")).isEqualTo(MCP_RESOURCE);
        assertThat(policy.resolveForAuthorization(MCP_RESOURCE, "client-1")).isEqualTo(MCP_RESOURCE);
    }

    @Test
    void resolveForAuthorization_apiAudience_throwsInvalidTarget() {
        assertThatThrownBy(() -> policy.resolveForAuthorization(API_AUDIENCE, "client-1"))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_TARGET));
    }

    @Test
    void resolveForAuthorization_unknown_throwsInvalidTarget() {
        assertThatThrownBy(() -> policy.resolveForAuthorization("http://evil.example", "client-1"))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_TARGET));
        assertThatThrownBy(() -> policy.resolveForAuthorization("http://localhost:8181/mcp", "client-1"))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_TARGET));
    }

    @Test
    void resolveForExchange_nonApi_throwsInvalidTarget() {
        assertThat(policy.resolveForExchange(API_AUDIENCE)).isEqualTo(API_AUDIENCE);
        assertThatThrownBy(() -> policy.resolveForExchange(MCP_RESOURCE))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_TARGET));
        assertThatThrownBy(() -> policy.resolveForExchange(null))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_TARGET));
    }

    @Test
    void resourceParam_multipleValues_throwsInvalidTarget() {
        var request = new MockHttpServletRequest();
        request.addParameter("resource", MCP_RESOURCE, "http://localhost:8181/");

        assertThatThrownBy(() -> policy.resourceParam(request))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_TARGET));
    }

    @Test
    void boundResourceOf_mcpClient_returnsMcpResource() {
        assertThat(policy.boundResourceOf("aj-mcp-server")).contains(MCP_RESOURCE);
        assertThat(policy.boundResourceOf("some-dcr-client")).isEmpty();
    }
}
