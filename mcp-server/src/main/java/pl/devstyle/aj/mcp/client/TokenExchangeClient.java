package pl.devstyle.aj.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.devstyle.aj.mcp.config.AjProperties;
import pl.devstyle.aj.mcp.exception.McpToolException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * RFC 8693 token exchange: trades the caller's MCP token for a short-lived, backend-audience token.
 * Called once per tool call; no caching and no retry.
 */
@Slf4j
@Component
public class TokenExchangeClient {

    static final String TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper ERROR_READER = new ObjectMapper();

    private final AjProperties properties;
    private final RestClient restClient;

    public TokenExchangeClient(AjProperties properties) {
        this.properties = properties;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public String exchange(String subjectToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", TOKEN_EXCHANGE_GRANT_TYPE);
        form.add("subject_token", subjectToken);
        form.add("subject_token_type", ACCESS_TOKEN_TYPE);
        form.add("requested_token_type", ACCESS_TOKEN_TYPE);
        form.add("resource", properties.backendResource());

        TokenExchangeResponse response;
        try {
            response = restClient.post()
                    .uri(properties.tokenUri())
                    .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenExchangeResponse.class);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String error = errorCode(ex);
            log.error("Token exchange rejected by authorization server: status={}, error={}", status, error);
            if (status == 400 && "invalid_scope".equals(error)) {
                throw McpToolException.apiError("Insufficient permissions");
            }
            throw McpToolException.apiError("Backend authorization failed");
        } catch (RestClientException ex) {
            log.error("Token exchange failed: {}", ex.getClass().getSimpleName());
            throw McpToolException.apiError("Backend authorization failed");
        }

        if (!isValid(response)) {
            log.error("Token exchange returned an unexpected response: issued_token_type={}, token_type={}",
                    response == null ? null : response.issuedTokenType(),
                    response == null ? null : response.tokenType());
            throw McpToolException.apiError("Backend authorization failed");
        }
        return response.accessToken();
    }

    private static boolean isValid(TokenExchangeResponse response) {
        return response != null
                && response.accessToken() != null && !response.accessToken().isBlank()
                && ACCESS_TOKEN_TYPE.equals(response.issuedTokenType())
                && "Bearer".equalsIgnoreCase(response.tokenType());
    }

    private String basicAuthorization() {
        var credentials = urlEncode(properties.oauth().clientId()) + ":" + urlEncode(properties.oauth().clientSecret());
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String errorCode(RestClientResponseException ex) {
        try {
            return ERROR_READER.readTree(ex.getResponseBodyAsByteArray()).path("error").asText(null);
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
