package pl.devstyle.aj.mcp.security;

import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.net.URI;
import java.util.Collection;
import java.util.List;

/**
 * Accepts an introspected token only when its {@code aud} names this MCP server (RFC 8707 audience binding).
 * The issuer is not checked: the introspecting authorization server is the authority.
 */
public class AudienceValidatingIntrospector implements OpaqueTokenIntrospector {

    private final OpaqueTokenIntrospector delegate;
    private final String resource;

    public AudienceValidatingIntrospector(OpaqueTokenIntrospector delegate, String resource) {
        this.delegate = delegate;
        this.resource = canonical(resource);
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        var principal = delegate.introspect(token);
        if (audiences(principal).stream().map(AudienceValidatingIntrospector::canonical).noneMatch(resource::equals)) {
            throw new BadOpaqueTokenException("Token audience does not include this MCP server");
        }
        return principal;
    }

    private static Collection<?> audiences(OAuth2AuthenticatedPrincipal principal) {
        Object aud = principal.getAttribute(OAuth2TokenIntrospectionClaimNames.AUD);
        if (aud instanceof Collection<?> values) {
            return values;
        }
        return aud == null ? List.of() : List.of(aud);
    }

    /** Origin-only URIs compare without one trailing slash; any other URI must match exactly. */
    private static String canonical(Object value) {
        var uri = String.valueOf(value);
        if (uri.endsWith("/") && isOriginOnly(uri)) {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private static boolean isOriginOnly(String uri) {
        try {
            var parsed = URI.create(uri);
            return parsed.getRawQuery() == null && parsed.getRawFragment() == null && "/".equals(parsed.getRawPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
