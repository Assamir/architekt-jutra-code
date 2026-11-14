package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class ResourcePolicy {

    private static final String RESOURCE_PARAM = "resource";

    private final OAuth2Properties properties;

    public ResourcePolicy(OAuth2Properties properties) {
        this.properties = properties;
    }

    public String canonical(String uri) {
        if (uri == null || !uri.endsWith("/")) {
            return uri;
        }
        try {
            var parsed = new URI(uri);
            boolean originOnly = parsed.getScheme() != null
                    && parsed.getRawAuthority() != null
                    && "/".equals(parsed.getRawPath())
                    && parsed.getRawQuery() == null
                    && parsed.getRawFragment() == null;
            return originOnly ? uri.substring(0, uri.length() - 1) : uri;
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    public String resolveForAuthorization(String resourceParam, String clientId) {
        if (resourceParam == null || resourceParam.isBlank()) {
            log.warn("OAuth2 resource defaulted | client_id={}", clientId);
            return properties.mcpResource();
        }
        if (canonical(resourceParam).equals(properties.mcpResource())) {
            return properties.mcpResource();
        }
        throw new OAuth2RequestException(OAuth2Error.INVALID_TARGET, "Unknown resource");
    }

    public String resolveForExchange(String resourceParam) {
        if (properties.apiAudience().equals(resourceParam)) {
            return properties.apiAudience();
        }
        throw new OAuth2RequestException(OAuth2Error.INVALID_TARGET, "The resource must be the API audience");
    }

    public Optional<String> boundResourceOf(String clientId) {
        return properties.mcpClientId().equals(clientId)
                ? Optional.of(properties.mcpResource())
                : Optional.empty();
    }

    public boolean audienceContains(Set<String> audience, String resource) {
        if (audience == null || resource == null) {
            return false;
        }
        String canonicalResource = canonical(resource);
        return audience.stream().map(this::canonical).anyMatch(canonicalResource::equals);
    }

    public Optional<String> resourceParam(HttpServletRequest request) {
        String[] values = request.getParameterValues(RESOURCE_PARAM);
        if (values == null || values.length == 0) {
            return Optional.empty();
        }
        if (values.length > 1) {
            throw new OAuth2RequestException(OAuth2Error.INVALID_TARGET, "Only one resource parameter is allowed");
        }
        return values[0].isBlank() ? Optional.empty() : Optional.of(values[0]);
    }
}
