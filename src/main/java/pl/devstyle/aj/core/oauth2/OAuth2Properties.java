package pl.devstyle.aj.core.oauth2;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.oauth2")
public record OAuth2Properties(String issuer, String mcpResource, String mcpClientId, String mcpClientSecret) {

    private static final String API_AUDIENCE_PATH = "/api";

    public OAuth2Properties {
        issuer = stripTrailingSlash(requireNonBlank(issuer, "app.oauth2.issuer"));
        mcpResource = stripTrailingSlash(requireNonBlank(mcpResource, "app.oauth2.mcp-resource"));
        requireNonBlank(mcpClientId, "app.oauth2.mcp-client-id");
        requireSeparateFromApi(issuer, mcpResource);
    }

    public String apiAudience() {
        return issuer + API_AUDIENCE_PATH;
    }

    @Override
    public String toString() {
        var maskedSecret = mcpClientSecret == null || mcpClientSecret.isBlank() ? "<blank>" : "***";
        return "OAuth2Properties[issuer=" + issuer + ", mcpResource=" + mcpResource
                + ", mcpClientId=" + mcpClientId + ", mcpClientSecret=" + maskedSecret + "]";
    }

    private static String requireNonBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    /**
     * An MCP resource equal to the issuer or inside the API audience would let MCP tokens pass as API tokens.
     */
    private static void requireSeparateFromApi(String issuer, String mcpResource) {
        String apiAudience = issuer + API_AUDIENCE_PATH;
        if (mcpResource.equals(issuer) || mcpResource.equals(apiAudience)
                || mcpResource.startsWith(apiAudience + "/")) {
            throw new IllegalArgumentException(
                    "app.oauth2.mcp-resource must differ from app.oauth2.issuer and lie outside " + apiAudience);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
