package pl.devstyle.aj.core.oauth2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import pl.devstyle.aj.core.security.JwtTokenProvider.ParsedToken;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * RFC 7662 introspection response. An inactive token carries only {@code "active": false}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntrospectionResponse(
        @JsonProperty("active") boolean active,
        @JsonProperty("sub") String sub,
        @JsonProperty("scope") String scope,
        @JsonProperty("aud") List<String> aud,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("iss") String iss,
        @JsonProperty("iat") Long iat,
        @JsonProperty("exp") Long exp,
        @JsonProperty("jti") String jti,
        @JsonProperty("token_type") String tokenType
) {

    private static final String BEARER = "Bearer";

    public static IntrospectionResponse inactive() {
        return new IntrospectionResponse(false, null, null, null, null, null, null, null, null, null);
    }

    public static IntrospectionResponse active(ParsedToken token, String issuer) {
        return new IntrospectionResponse(true, token.subject(), ScopePolicy.toScopeString(token.scopes()),
                sorted(token.audience()), token.clientId(), issuer, epochSeconds(token.issuedAt()),
                epochSeconds(token.expiresAt()), token.jti(), BEARER);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static Long epochSeconds(Instant instant) {
        return instant != null ? instant.getEpochSecond() : null;
    }
}
