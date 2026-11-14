package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import pl.devstyle.aj.core.security.JwtTokenProvider.ParsedToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
public class OAuth2AuthorizationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_ENDPOINT = "/oauth2/authorize";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RegisteredClientRepository registeredClientRepository;
    private final AuthorizationCodeService authorizationCodeService;
    private final ResourcePolicy resourcePolicy;
    private final OAuth2ErrorWriter errorWriter;

    public OAuth2AuthorizationFilter(RegisteredClientRepository registeredClientRepository,
                                     AuthorizationCodeService authorizationCodeService,
                                     ResourcePolicy resourcePolicy,
                                     OAuth2ErrorWriter errorWriter) {
        this.registeredClientRepository = registeredClientRepository;
        this.authorizationCodeService = authorizationCodeService;
        this.resourcePolicy = resourcePolicy;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!("POST".equals(request.getMethod()) && AUTHORIZATION_ENDPOINT.equals(request.getRequestURI()))) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            handleAuthorizationRequest(request, response);
        } catch (OAuth2RequestException e) {
            log.warn("OAuth2 authorization rejected | client_id={} | error={}",
                    OAuth2LogValues.clientId(request), e.getError().getError());
            errorWriter.write(response, e.getError(), e.getDescription());
        } catch (RuntimeException e) {
            log.error("OAuth2 authorization error | client_id={} | error_type={}",
                    OAuth2LogValues.clientId(request), e.getClass().getName());
            log.debug("OAuth2 authorization error details", e);
            errorWriter.write(response, OAuth2Error.SERVER_ERROR,
                    "An error occurred while processing the authorization request");
        }
    }

    private void handleAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        ParsedToken session = sessionPrincipal();

        String clientId = request.getParameter("client_id");
        String redirectUri = request.getParameter("redirect_uri");
        String responseType = request.getParameter("response_type");
        String scope = request.getParameter("scope");
        String state = request.getParameter("state");
        String codeChallenge = request.getParameter("code_challenge");
        String codeChallengeMethod = request.getParameter("code_challenge_method");

        if (clientId == null || redirectUri == null || responseType == null) {
            throw invalidRequest("Missing required parameters: client_id, redirect_uri, response_type");
        }
        if (!"code".equals(responseType)) {
            throw new OAuth2RequestException(OAuth2Error.UNSUPPORTED_RESPONSE_TYPE,
                    "Unsupported response_type. Only 'code' is supported");
        }

        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw invalidRequest("Invalid client_id");
        }
        OAuth2LogValues.recordClientId(request, client.getClientId());
        if (!client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE)) {
            throw new OAuth2RequestException(OAuth2Error.UNAUTHORIZED_CLIENT,
                    "The client is not authorized to use the authorization_code grant");
        }

        validateRedirectUri(redirectUri, client);
        validateScopes(scope, client);
        validatePkceParameters(codeChallenge, codeChallengeMethod, client);

        String resource = resourcePolicy.resolveForAuthorization(
                resourcePolicy.resourceParam(request).orElse(null), clientId);
        String grantedScope = ScopePolicy.toScopeString(
                ScopePolicy.grantAtAuthorization(scope, session.permissions(), client.getScopes()));

        String authorizationCode = generateAuthorizationCode();
        authorizationCodeService.storeAuthorizationCode(
                authorizationCode, clientId, redirectUri, grantedScope,
                codeChallenge, codeChallengeMethod, session.subject(), resource
        );

        String redirectUrl = authorizationRedirect(redirectUri, authorizationCode, state);

        log.info("Authorization code issued | client_id={} | user={} | scope={} | resource={}",
                client.getClientId(), session.subject(), grantedScope, resource);

        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", redirectUrl);
    }

    /**
     * Appends {@code code} and {@code state} to the registered redirect URI, keeping its existing (already encoded)
     * query and strictly percent-encoding the appended values.
     */
    private static String authorizationRedirect(String redirectUri, String code, String state) {
        var redirect = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", UriUtils.encode(code, StandardCharsets.UTF_8));
        if (state != null && !state.isEmpty()) {
            redirect.queryParam("state", UriUtils.encode(state, StandardCharsets.UTF_8));
        }
        return redirect.build(true).toUriString();
    }

    private static ParsedToken sessionPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getDetails() instanceof ParsedToken parsed && parsed.isSessionToken()) {
            return parsed;
        }
        throw new OAuth2RequestException(OAuth2Error.ACCESS_DENIED, "User must be authenticated");
    }

    private String generateAuthorizationCode() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validateRedirectUri(String redirectUri, RegisteredClient client) {
        if (!client.getRedirectUris().contains(redirectUri)) {
            throw invalidRequest("redirect_uri not registered for this client");
        }
    }

    private void validateScopes(String scope, RegisteredClient client) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        for (String requestedScope : scope.trim().split("\\s+")) {
            if (!client.getScopes().contains(requestedScope)) {
                throw new OAuth2RequestException(OAuth2Error.INVALID_SCOPE,
                        "Scope not allowed for this client: " + requestedScope);
            }
        }
    }

    private void validatePkceParameters(String codeChallenge, String codeChallengeMethod, RegisteredClient client) {
        boolean isPkceProvided = codeChallenge != null || codeChallengeMethod != null;
        boolean isPublicClient = client.getClientAuthenticationMethods().stream()
                .anyMatch(method -> "none".equals(method.getValue()));

        if (isPublicClient && !isPkceProvided) {
            throw invalidRequest(
                    "PKCE is required for public clients. Provide code_challenge and code_challenge_method parameters");
        }

        if (isPkceProvided) {
            if (codeChallenge == null || codeChallengeMethod == null) {
                throw invalidRequest("Both code_challenge and code_challenge_method must be provided when using PKCE");
            }
            if (!"S256".equals(codeChallengeMethod)) {
                throw invalidRequest("Unsupported code_challenge_method. Only 'S256' is supported");
            }
            if (codeChallenge.length() < 43 || codeChallenge.length() > 128) {
                throw invalidRequest("Invalid code_challenge length. Must be 43-128 characters");
            }
            if (!codeChallenge.matches("^[A-Za-z0-9_-]+$")) {
                throw invalidRequest("Invalid code_challenge format. Must be base64url-encoded");
            }
        }
    }

    private static OAuth2RequestException invalidRequest(String description) {
        return new OAuth2RequestException(OAuth2Error.INVALID_REQUEST, description);
    }
}
