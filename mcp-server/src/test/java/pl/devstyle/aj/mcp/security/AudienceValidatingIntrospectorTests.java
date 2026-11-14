package pl.devstyle.aj.mcp.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceValidatingIntrospectorTests {

    private static final String MCP_RESOURCE = "http://localhost:8181";

    @Test
    void introspect_matchingAudience_returnsPrincipal() {
        var principal = principalWithAudience(MCP_RESOURCE);
        var introspector = new AudienceValidatingIntrospector(token -> principal, MCP_RESOURCE);

        assertThat(introspector.introspect("token")).isSameAs(principal);
    }

    @Test
    void introspect_slashVariantAudience_returnsPrincipal() {
        var principal = principalWithAudience(MCP_RESOURCE + "/");
        var introspector = new AudienceValidatingIntrospector(token -> principal, MCP_RESOURCE);

        assertThat(introspector.introspect("token")).isSameAs(principal);
    }

    @Test
    void introspect_foreignAudience_throwsBadOpaqueToken() {
        OpaqueTokenIntrospector delegate = token -> principalWithAudience("http://localhost:8180/api");
        var introspector = new AudienceValidatingIntrospector(delegate, MCP_RESOURCE);

        assertThatThrownBy(() -> introspector.introspect("token"))
                .isInstanceOf(BadOpaqueTokenException.class);
    }

    private static OAuth2IntrospectionAuthenticatedPrincipal principalWithAudience(String audience) {
        return new OAuth2IntrospectionAuthenticatedPrincipal(
                Map.of("active", true, "sub", "admin", "aud", List.of(audience)), List.of());
    }
}
