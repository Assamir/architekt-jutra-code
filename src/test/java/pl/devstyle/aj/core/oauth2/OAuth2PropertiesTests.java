package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2PropertiesTests {

    @Test
    void constructor_trailingSlash_isStripped() {
        var properties = new OAuth2Properties("http://localhost:8180/", "http://localhost:8181/", "aj-mcp-server", "secret");

        assertThat(properties.issuer()).isEqualTo("http://localhost:8180");
        assertThat(properties.mcpResource()).isEqualTo("http://localhost:8181");
        assertThat(properties.apiAudience()).isEqualTo("http://localhost:8180/api");
    }

    @Test
    void constructor_blankMcpResource_throws() {
        assertThatThrownBy(() -> new OAuth2Properties("http://localhost:8180", " ", "aj-mcp-server", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.oauth2.mcp-resource");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8180", "http://localhost:8180/", "http://localhost:8180/api",
            "http://localhost:8180/api/", "http://localhost:8180/api/mcp"})
    void constructor_mcpResourceOverlapsIssuerOrApiAudience_throws(String mcpResource) {
        assertThatThrownBy(() -> new OAuth2Properties("http://localhost:8180", mcpResource, "aj-mcp-server", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.oauth2.mcp-resource");
    }

    @Test
    void constructor_mcpResourceSharingApiPrefixOnly_isAccepted() {
        var properties = new OAuth2Properties("http://localhost:8180", "http://localhost:8180/apimcp",
                "aj-mcp-server", "secret");

        assertThat(properties.mcpResource()).isEqualTo("http://localhost:8180/apimcp");
    }

    @Test
    void toString_masksClientSecret() {
        var withSecret = new OAuth2Properties("http://localhost:8180", "http://localhost:8181", "aj-mcp-server", "s3cr3t-value");
        var withoutSecret = new OAuth2Properties("http://localhost:8180", "http://localhost:8181", "aj-mcp-server", "");

        assertThat(withSecret.toString()).doesNotContain("s3cr3t-value").contains("***");
        assertThat(withoutSecret.toString()).contains("<blank>");
    }
}
