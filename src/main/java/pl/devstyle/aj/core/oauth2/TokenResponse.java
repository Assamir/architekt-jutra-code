package pl.devstyle.aj.core.oauth2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("scope") String scope,
        @JsonProperty("issued_token_type") String issuedTokenType
) {

    private static final String BEARER = "Bearer";

    public static TokenResponse withRefreshToken(String accessToken, Instant issuedAt, Instant expiresAt,
                                                 String refreshToken, String scope) {
        return new TokenResponse(accessToken, BEARER, secondsBetween(issuedAt, expiresAt), refreshToken, scope, null);
    }

    public static TokenResponse issuedToken(String accessToken, Instant issuedAt, Instant expiresAt, String scope,
                                            String issuedTokenType) {
        return new TokenResponse(accessToken, BEARER, secondsBetween(issuedAt, expiresAt), null, scope,
                issuedTokenType);
    }

    private static long secondsBetween(Instant issuedAt, Instant expiresAt) {
        return Math.max(0, Duration.between(issuedAt, expiresAt).getSeconds());
    }
}
