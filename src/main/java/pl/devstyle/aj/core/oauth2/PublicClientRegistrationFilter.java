package pl.devstyle.aj.core.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.oidc.OidcClientRegistration;
import org.springframework.security.oauth2.server.authorization.oidc.http.converter.OidcClientRegistrationHttpMessageConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class PublicClientRegistrationFilter extends OncePerRequestFilter {

    private static final String REGISTRATION_ENDPOINT = "/oauth2/register";
    private static final List<String> ALLOWED_SCOPES = ScopePolicy.SUPPORTED_SCOPES;
    private static final List<String> DEFAULT_RESPONSE_TYPES = List.of("code");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final OidcClientRegistrationHttpMessageConverter oidcConverter;
    private final OAuth2ErrorWriter errorWriter;

    public PublicClientRegistrationFilter(RegisteredClientRepository registeredClientRepository,
                                          PasswordEncoder passwordEncoder,
                                          OAuth2ErrorWriter errorWriter) {
        this.registeredClientRepository = registeredClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.errorWriter = errorWriter;
        this.objectMapper = new ObjectMapper();
        this.oidcConverter = new OidcClientRegistrationHttpMessageConverter();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!("POST".equals(request.getMethod()) && REGISTRATION_ENDPOINT.equals(request.getRequestURI()))) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            handleRegistrationRequest(request, response);
        } catch (IllegalArgumentException e) {
            log.warn("Client registration rejected | error={}", OAuth2Error.INVALID_CLIENT_METADATA.getError());
            errorWriter.write(response, OAuth2Error.INVALID_CLIENT_METADATA, e.getMessage());
        } catch (Exception e) {
            log.error("Client registration error | error_type={}", e.getClass().getName());
            log.debug("Client registration error details", e);
            errorWriter.write(response, OAuth2Error.SERVER_ERROR, "An error occurred while processing the registration request");
        }
    }

    @SuppressWarnings("unchecked")
    private void handleRegistrationRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> requestBody = objectMapper.readValue(request.getInputStream(), Map.class);

        String clientName = (String) requestBody.get("client_name");
        List<String> responseTypes = (List<String>) requestBody.get("response_types");
        List<String> grantTypes = (List<String>) requestBody.get("grant_types");
        List<Object> redirectUris = (List<Object>) requestBody.get("redirect_uris");
        String tokenEndpointAuthMethod = (String) requestBody.get("token_endpoint_auth_method");
        String scope = (String) requestBody.get("scope");

        validateClientName(clientName);
        List<String> validatedRedirectUris = validateRedirectUris(redirectUris);
        List<String> scopes = (scope != null && !scope.trim().isEmpty())
                ? Arrays.asList(scope.trim().split("\\s+"))
                : ALLOWED_SCOPES;
        validateScope(scopes);

        String clientId = UUID.randomUUID().toString();
        String clientSecret = UUID.randomUUID().toString();

        var builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientName)
                .clientIdIssuedAt(Instant.now())
                .clientSecret(passwordEncoder.encode(clientSecret))
                .redirectUris(uris -> uris.addAll(validatedRedirectUris))
                .scopes(scopeSet -> scopeSet.addAll(scopes));

        configureAuthenticationMethod(builder, tokenEndpointAuthMethod);
        configureGrantTypes(builder, grantTypes);

        var registeredClient = builder.build();
        List<String> registeredGrantTypes = registeredClient.getAuthorizationGrantTypes().stream()
                .map(AuthorizationGrantType::getValue)
                .sorted()
                .toList();

        var oidcRegistration = OidcClientRegistration.builder()
                .clientId(registeredClient.getClientId())
                .clientSecret(clientSecret)
                .clientName(registeredClient.getClientName())
                .clientIdIssuedAt(registeredClient.getClientIdIssuedAt())
                .redirectUris(uris -> uris.addAll(registeredClient.getRedirectUris()))
                .grantTypes(grants -> grants.addAll(registeredGrantTypes))
                .responseTypes(types -> types.addAll(
                        responseTypes == null || responseTypes.isEmpty() ? DEFAULT_RESPONSE_TYPES : responseTypes))
                .scopes(scopeSet -> scopeSet.addAll(registeredClient.getScopes()))
                .tokenEndpointAuthenticationMethod(tokenEndpointAuthMethod)
                .clientSecretExpiresAt(Instant.ofEpochSecond(0))
                .build();

        registeredClientRepository.save(registeredClient);

        var httpResponse = new ServletServerHttpResponse(response);
        httpResponse.setStatusCode(HttpStatus.CREATED);
        oidcConverter.write(oidcRegistration, MediaType.APPLICATION_JSON, httpResponse);

        log.info("Client registered | client_id={} | grant_types={} | scopes={} | auth_method={}",
                clientId, registeredGrantTypes, scopes, tokenEndpointAuthMethod);
    }

    private void configureAuthenticationMethod(RegisteredClient.Builder builder, String tokenEndpointAuthMethod) {
        if (tokenEndpointAuthMethod == null) {
            tokenEndpointAuthMethod = "client_secret_post";
        }
        switch (tokenEndpointAuthMethod) {
            case "client_secret_post" -> builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
            case "client_secret_basic" -> builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            case "none" -> builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
            default -> throw new IllegalArgumentException(
                    "token_endpoint_auth_method must be 'client_secret_post', 'client_secret_basic', or 'none'");
        }
    }

    private void configureGrantTypes(RegisteredClient.Builder builder, List<String> grantTypes) {
        if (grantTypes == null || grantTypes.isEmpty()) {
            builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
            return;
        }
        for (String grantType : grantTypes) {
            switch (grantType) {
                case "authorization_code" -> builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
                case "refresh_token" -> builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
                default -> throw new IllegalArgumentException("Unsupported grant type: " + grantType);
            }
        }
    }

    private void validateClientName(String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            throw new IllegalArgumentException("client_name is required and cannot be empty");
        }
        if (clientName.length() > 255) {
            throw new IllegalArgumentException("client_name cannot exceed 255 characters");
        }
    }

    /**
     * Every supported client uses the authorization code flow (it is also the default grant), so redirect URIs are
     * always required. Each must parse as an absolute ASCII URI without a fragment, and be either {@code https} or
     * {@code http} on an exact loopback host.
     */
    private List<String> validateRedirectUris(List<Object> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect_uris is required");
        }
        return redirectUris.stream().map(this::validateRedirectUri).toList();
    }

    private String validateRedirectUri(Object value) {
        if (!(value instanceof String uri) || uri.isBlank()) {
            throw new IllegalArgumentException("redirect_uris must contain non-empty strings");
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("redirect_uri is not a valid URI: " + uri);
        }
        if (!parsed.toASCIIString().equals(uri)) {
            throw new IllegalArgumentException("redirect_uri must contain only ASCII characters: " + uri);
        }
        if (parsed.getRawFragment() != null || uri.contains("#")) {
            throw new IllegalArgumentException("redirect_uri cannot contain fragments: " + uri);
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        boolean https = "https".equalsIgnoreCase(scheme) && host != null;
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme) && host != null
                && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        if (!https && !loopbackHttp || parsed.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "redirect_uri must be an HTTPS URL or an http URL on localhost, 127.0.0.1 or [::1]: " + uri);
        }
        return uri;
    }

    private void validateScope(List<String> scopes) {
        for (String requestedScope : scopes) {
            if (!ALLOWED_SCOPES.contains(requestedScope.trim())) {
                throw new IllegalArgumentException("Unsupported scope: " + requestedScope + ". Allowed scopes: " + ALLOWED_SCOPES);
            }
        }
    }
}
