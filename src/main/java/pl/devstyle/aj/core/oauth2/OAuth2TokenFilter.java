package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.devstyle.aj.core.oauth2.AuthorizationCodeService.AuthorizationCodeData;
import pl.devstyle.aj.core.security.JwtTokenProvider;
import pl.devstyle.aj.core.security.OAuth2TokenClaims;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class OAuth2TokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_ENDPOINT = "/oauth2/token";
    private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
    private static final String REFRESH_TOKEN_GRANT = "refresh_token";
    private static final long ACCESS_TOKEN_TTL_SECONDS = 900;
    private static final String INVALID_CODE = "Authorization code is invalid or has expired";

    private final AuthorizationCodeService authorizationCodeService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ClientAuthenticator clientAuthenticator;
    private final ResourcePolicy resourcePolicy;
    private final TokenExchangeGrantHandler tokenExchangeGrantHandler;
    private final OAuth2ErrorWriter errorWriter;
    private final ObjectMapper objectMapper;

    public OAuth2TokenFilter(AuthorizationCodeService authorizationCodeService,
                             RefreshTokenService refreshTokenService,
                             JwtTokenProvider jwtTokenProvider,
                             ClientAuthenticator clientAuthenticator,
                             ResourcePolicy resourcePolicy,
                             TokenExchangeGrantHandler tokenExchangeGrantHandler,
                             OAuth2ErrorWriter errorWriter,
                             ObjectMapper objectMapper) {
        this.authorizationCodeService = authorizationCodeService;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clientAuthenticator = clientAuthenticator;
        this.resourcePolicy = resourcePolicy;
        this.tokenExchangeGrantHandler = tokenExchangeGrantHandler;
        this.errorWriter = errorWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!(TOKEN_ENDPOINT.equals(request.getRequestURI()) && "POST".equals(request.getMethod()))) {
            filterChain.doFilter(request, response);
            return;
        }

        String grantType = request.getParameter("grant_type");
        try {
            TokenResponse tokenResponse = switch (grantType == null ? "" : grantType) {
                case AUTHORIZATION_CODE_GRANT -> handleAuthorizationCodeGrant(request);
                case REFRESH_TOKEN_GRANT -> handleRefreshTokenGrant(request);
                case TokenExchangeGrantHandler.GRANT_TYPE -> tokenExchangeGrantHandler.handle(request);
                default -> throw new OAuth2RequestException(OAuth2Error.UNSUPPORTED_GRANT_TYPE,
                        "Unsupported grant type");
            };
            writeTokenResponse(response, tokenResponse);
        } catch (OAuth2RequestException e) {
            log.warn("OAuth2 token request rejected | grant_type={} | client_id={} | error={}",
                    OAuth2LogValues.sanitize(grantType), OAuth2LogValues.clientId(request), e.getError().getError());
            errorWriter.write(response, e.getError(), e.getDescription());
        } catch (RuntimeException e) {
            log.error("OAuth2 token endpoint error | grant_type={} | client_id={} | error_type={}",
                    OAuth2LogValues.sanitize(grantType), OAuth2LogValues.clientId(request), e.getClass().getName());
            log.debug("OAuth2 token endpoint error details", e);
            if (!response.isCommitted()) {
                errorWriter.write(response, OAuth2Error.SERVER_ERROR, "Internal server error");
            }
        }
    }

    private TokenResponse handleAuthorizationCodeGrant(HttpServletRequest request) {
        String code = requiredParameter(request, "code");
        String redirectUri = requiredParameter(request, "redirect_uri");

        AuthorizationCodeData data = authorizationCodeService.consumeAuthorizationCode(code)
                .orElseThrow(() -> invalidGrant(INVALID_CODE));

        RegisteredClient client = clientAuthenticator.identify(request);
        requireGrantType(client, AuthorizationGrantType.AUTHORIZATION_CODE);
        if (!client.getClientId().equals(data.clientId())) {
            throw invalidGrant(INVALID_CODE);
        }
        if (!redirectUri.equals(data.redirectUri())) {
            throw invalidGrant(INVALID_CODE);
        }
        if (data.codeChallenge() != null
                && !validatePkceCodeVerifier(request.getParameter("code_verifier"), data.codeChallenge(),
                data.codeChallengeMethod())) {
            throw invalidGrant(INVALID_CODE);
        }
        requestedResource(request, data.clientId()).ifPresent(resource -> {
            if (!resource.equals(data.resource())) {
                throw invalidGrant("The resource does not match the authorization grant");
            }
        });

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ACCESS_TOKEN_TTL_SECONDS);
        String accessToken = mintAccessToken(data.username(), data.scope(), data.resource(), data.clientId(),
                expiresAt);
        String refreshToken = client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)
                ? refreshTokenService.issueToken(data.username(), data.scope(), data.clientId(), data.resource())
                : null;

        log.info("OAuth2 token issued | grant_type={} | client_id={} | user={} | scope={} | resource={}",
                AUTHORIZATION_CODE_GRANT, data.clientId(), data.username(), data.scope(), data.resource());
        return TokenResponse.withRefreshToken(accessToken, issuedAt, expiresAt, refreshToken, data.scope());
    }

    private TokenResponse handleRefreshTokenGrant(HttpServletRequest request) {
        String refreshToken = requiredParameter(request, "refresh_token");
        RegisteredClient client = clientAuthenticator.identify(request);
        requireGrantType(client, AuthorizationGrantType.REFRESH_TOKEN);
        Optional<String> resource = requestedResource(request, client.getClientId());

        var rotation = refreshTokenService.consumeAndRotate(refreshToken, client.getClientId(), resource)
                .orElseThrow(() -> invalidGrant("The provided refresh token is invalid, expired, or revoked"));
        var data = rotation.originalData();

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ACCESS_TOKEN_TTL_SECONDS);
        String accessToken = mintAccessToken(data.username(), data.scope(), data.resource(), data.clientId(),
                expiresAt);

        log.info("OAuth2 token refreshed | grant_type={} | client_id={} | user={} | scope={} | resource={}",
                REFRESH_TOKEN_GRANT, data.clientId(), data.username(), data.scope(), data.resource());
        return TokenResponse.withRefreshToken(accessToken, issuedAt, expiresAt, rotation.newToken(), data.scope());
    }

    private static void requireGrantType(RegisteredClient client, AuthorizationGrantType grantType) {
        if (!client.getAuthorizationGrantTypes().contains(grantType)) {
            throw new OAuth2RequestException(OAuth2Error.UNAUTHORIZED_CLIENT,
                    "The client is not authorized to use the " + grantType.getValue() + " grant");
        }
    }

    private Optional<String> requestedResource(HttpServletRequest request, String clientId) {
        return resourcePolicy.resourceParam(request)
                .map(resource -> resourcePolicy.resolveForAuthorization(resource, clientId));
    }

    private String mintAccessToken(String username, String scope, String resource, String clientId,
                                   Instant expiresAt) {
        return jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims(username, scopesOf(scope),
                resource, clientId, null, expiresAt));
    }

    private static Set<String> scopesOf(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.trim().split("\\s+")).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void writeTokenResponse(HttpServletResponse response, TokenResponse tokenResponse) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(tokenResponse));
        response.getOutputStream().flush();
    }

    private static String requiredParameter(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new OAuth2RequestException(OAuth2Error.INVALID_REQUEST, "Missing required parameter: " + name);
        }
        return value;
    }

    private static OAuth2RequestException invalidGrant(String description) {
        return new OAuth2RequestException(OAuth2Error.INVALID_GRANT, description);
    }

    private boolean validatePkceCodeVerifier(String codeVerifier, String codeChallenge, String codeChallengeMethod) {
        if (codeVerifier == null || codeVerifier.isBlank()) {
            log.warn("PKCE validation failed | reason=Missing code_verifier");
            return false;
        }
        if (codeVerifier.length() < 43 || codeVerifier.length() > 128) {
            log.warn("PKCE validation failed | reason=Invalid code_verifier length");
            return false;
        }
        if (!codeVerifier.matches("^[A-Za-z0-9_\\-\\.~]+$")) {
            log.warn("PKCE validation failed | reason=Invalid code_verifier format");
            return false;
        }
        if (!"S256".equals(codeChallengeMethod)) {
            log.warn("PKCE validation failed | reason=Unsupported code_challenge_method");
            return false;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            if (!computedChallenge.equals(codeChallenge)) {
                log.warn("PKCE validation failed | reason=code_verifier does not match code_challenge");
                return false;
            }
            return true;
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available for PKCE validation", e);
            return false;
        }
    }
}
