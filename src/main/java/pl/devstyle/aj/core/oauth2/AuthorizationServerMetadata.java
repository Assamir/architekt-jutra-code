package pl.devstyle.aj.core.oauth2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * RFC 8414 authorization server metadata, built from the configured issuer only (never from request headers).
 */
public record AuthorizationServerMetadata(
        @JsonProperty("issuer") String issuer,
        @JsonProperty("authorization_endpoint") String authorizationEndpoint,
        @JsonProperty("token_endpoint") String tokenEndpoint,
        @JsonProperty("registration_endpoint") String registrationEndpoint,
        @JsonProperty("introspection_endpoint") String introspectionEndpoint,
        @JsonProperty("introspection_endpoint_auth_methods_supported") List<String> introspectionEndpointAuthMethodsSupported,
        @JsonProperty("grant_types_supported") List<String> grantTypesSupported,
        @JsonProperty("response_types_supported") List<String> responseTypesSupported,
        @JsonProperty("code_challenge_methods_supported") List<String> codeChallengeMethodsSupported,
        @JsonProperty("token_endpoint_auth_methods_supported") List<String> tokenEndpointAuthMethodsSupported,
        @JsonProperty("scopes_supported") List<String> scopesSupported
) {

    public static AuthorizationServerMetadata forIssuer(String issuer) {
        return new AuthorizationServerMetadata(
                issuer,
                issuer + "/oauth2/authorize",
                issuer + "/oauth2/token",
                issuer + "/oauth2/register",
                issuer + "/oauth2/introspect",
                List.of("client_secret_basic", "client_secret_post"),
                List.of("authorization_code", "refresh_token", TokenExchangeGrantHandler.GRANT_TYPE),
                List.of("code"),
                List.of("S256"),
                List.of("client_secret_post", "client_secret_basic", "none"),
                ScopePolicy.SUPPORTED_SCOPES);
    }
}
