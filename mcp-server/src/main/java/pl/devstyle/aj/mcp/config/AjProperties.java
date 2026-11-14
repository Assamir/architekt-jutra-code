package pl.devstyle.aj.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("aj")
public record AjProperties(@DefaultValue Backend backend, @DefaultValue Oauth oauth, @DefaultValue Mcp mcp) {

    public String introspectionUri() {
        return oauth.serverUrl() + "/oauth2/introspect";
    }

    public String tokenUri() {
        return oauth.serverUrl() + "/oauth2/token";
    }

    public String backendResource() {
        return oauth.serverUrl() + "/api";
    }

    public String resourceMetadataUrl() {
        return mcp.baseUrl() + "/.well-known/oauth-protected-resource";
    }

    public record Backend(String url) {

        public Backend {
            requireNonBlank(url, "aj.backend.url");
            url = stripTrailingSlash(url);
        }
    }

    public record Oauth(String serverUrl, String clientId, String clientSecret) {

        public Oauth {
            if (isBlank(clientSecret)) {
                throw new IllegalArgumentException(
                        "aj.oauth.client-secret is not set — export AJ_OAUTH_CLIENT_SECRET (start-dev does this)");
            }
            requireNonBlank(serverUrl, "aj.oauth.server-url");
            requireNonBlank(clientId, "aj.oauth.client-id");
            serverUrl = stripTrailingSlash(serverUrl);
        }

        @Override
        public String toString() {
            return "Oauth[serverUrl=" + serverUrl + ", clientId=" + clientId + ", clientSecret=***]";
        }
    }

    public record Mcp(String baseUrl) {

        public Mcp {
            requireNonBlank(baseUrl, "aj.mcp.base-url");
            baseUrl = stripTrailingSlash(baseUrl);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireNonBlank(String value, String property) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(property + " is not set");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
