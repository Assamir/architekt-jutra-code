package pl.devstyle.aj.mcp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenExchangeResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("issued_token_type") String issuedTokenType,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("scope") String scope) {

    @Override
    public String toString() {
        return "TokenExchangeResponse[accessToken=***, issuedTokenType=" + issuedTokenType
                + ", tokenType=" + tokenType + ", expiresIn=" + expiresIn + ", scope=" + scope + "]";
    }
}
