package pl.devstyle.aj.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import pl.devstyle.aj.mcp.client.AjApiClient;
import pl.devstyle.aj.mcp.exception.McpToolException;

@Configuration
public class RestClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RestClientConfig.class);

    private final AjProperties properties;

    public RestClientConfig(AjProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestClient ajRestClient() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(30_000);

        return RestClient.builder()
                .baseUrl(properties.backend().url())
                .requestFactory(requestFactory)
                .defaultStatusHandler(status -> status.is4xxClientError() || status.is5xxServerError(),
                        (request, response) -> {
                            int statusCode = response.getStatusCode().value();
                            String body = new String(response.getBody().readNBytes(8192));
                            LOG.warn("Backend API error: status={}, path={}", statusCode, request.getURI().getPath());

                            throw switch (statusCode) {
                                case 400 -> McpToolException.validationError(body);
                                case 401 -> McpToolException.apiError("Authentication required");
                                case 403 -> McpToolException.apiError("Insufficient permissions");
                                case 404 -> McpToolException.notFound(body);
                                default -> McpToolException.apiError(
                                        "Backend error (status %d). Please retry.".formatted(statusCode));
                            };
                        })
                .build();
    }

    @Bean
    public AjApiClient ajApiClient(RestClient ajRestClient) {
        var adapter = RestClientAdapter.create(ajRestClient);
        var factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(AjApiClient.class);
    }
}
