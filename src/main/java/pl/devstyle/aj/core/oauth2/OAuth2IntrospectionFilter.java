package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.devstyle.aj.core.security.JwtTokenProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * RFC 7662 token introspection for confidential resource-server clients. A token is reported active only when
 * its audience contains the resource bound to the calling client; every other case yields {@code active: false}.
 */
@Slf4j
public class OAuth2IntrospectionFilter extends OncePerRequestFilter {

    private static final String INTROSPECTION_ENDPOINT = "/oauth2/introspect";

    private final ClientAuthenticator clientAuthenticator;
    private final ResourcePolicy resourcePolicy;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuth2Properties properties;
    private final OAuth2ErrorWriter errorWriter;
    private final ObjectMapper objectMapper;

    public OAuth2IntrospectionFilter(ClientAuthenticator clientAuthenticator,
                                     ResourcePolicy resourcePolicy,
                                     JwtTokenProvider jwtTokenProvider,
                                     OAuth2Properties properties,
                                     OAuth2ErrorWriter errorWriter,
                                     ObjectMapper objectMapper) {
        this.clientAuthenticator = clientAuthenticator;
        this.resourcePolicy = resourcePolicy;
        this.jwtTokenProvider = jwtTokenProvider;
        this.properties = properties;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!(INTROSPECTION_ENDPOINT.equals(request.getRequestURI()) && "POST".equals(request.getMethod()))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        try {
            writeResponse(response, introspect(request));
        } catch (OAuth2RequestException e) {
            log.warn("OAuth2 introspection rejected | client_id={} | error={}",
                    OAuth2LogValues.clientId(request), e.getError().getError());
            errorWriter.write(response, e.getError(), e.getDescription());
        } catch (RuntimeException e) {
            log.error("OAuth2 introspection endpoint error | client_id={} | error_type={}",
                    OAuth2LogValues.clientId(request), e.getClass().getName());
            log.debug("OAuth2 introspection endpoint error details", e);
            if (!response.isCommitted()) {
                errorWriter.write(response, OAuth2Error.SERVER_ERROR, "Internal server error");
            }
        }
    }

    private IntrospectionResponse introspect(HttpServletRequest request) {
        RegisteredClient client = clientAuthenticator.authenticateConfidential(request);
        String token = request.getParameter("token");
        if (token == null || token.isBlank()) {
            throw new OAuth2RequestException(OAuth2Error.INVALID_REQUEST, "Missing required parameter: token");
        }

        Optional<String> boundResource = resourcePolicy.boundResourceOf(client.getClientId());
        return boundResource
                .flatMap(resource -> jwtTokenProvider.parseAccessToken(token)
                        .filter(parsed -> resourcePolicy.audienceContains(parsed.audience(), resource)))
                .map(parsed -> IntrospectionResponse.active(parsed, properties.issuer()))
                .orElseGet(IntrospectionResponse::inactive);
    }

    private void writeResponse(HttpServletResponse response, IntrospectionResponse introspection)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(introspection));
        response.getOutputStream().flush();
    }
}
