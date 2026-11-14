package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import pl.devstyle.aj.core.security.CustomUserDetailsService;
import pl.devstyle.aj.core.security.JwtTokenProvider;
import pl.devstyle.aj.core.security.JwtTokenProvider.ParsedToken;
import pl.devstyle.aj.core.security.OAuth2TokenClaims;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RFC 8693 token exchange: the MCP client trades a user's MCP access token (T1) for a short-lived delegated
 * token (T2) for the host API. Scopes are recomputed from the user's current permissions.
 */
@Slf4j
public class TokenExchangeGrantHandler {

    public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    private static final long DELEGATED_TOKEN_TTL_SECONDS = 300;
    private static final String PERMISSION_PREFIX = "PERMISSION_";
    private static final String INVALID_SUBJECT = "The subject token is invalid";

    private final ClientAuthenticator clientAuthenticator;
    private final ResourcePolicy resourcePolicy;
    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;

    public TokenExchangeGrantHandler(ClientAuthenticator clientAuthenticator, ResourcePolicy resourcePolicy,
                                     JwtTokenProvider jwtTokenProvider, CustomUserDetailsService userDetailsService) {
        this.clientAuthenticator = clientAuthenticator;
        this.resourcePolicy = resourcePolicy;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
    }

    public TokenResponse handle(HttpServletRequest request) {
        RegisteredClient client = clientAuthenticator.authenticateConfidential(request);
        String boundResource = resourcePolicy.boundResourceOf(client.getClientId())
                .filter(resource -> client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.TOKEN_EXCHANGE))
                .orElseThrow(() -> new OAuth2RequestException(OAuth2Error.UNAUTHORIZED_CLIENT,
                        "The client is not authorized to use token exchange"));

        String subjectToken = validateExchangeParameters(request);
        String audience = resourcePolicy.resolveForExchange(resourcePolicy.resourceParam(request).orElse(null));

        ParsedToken subject = jwtTokenProvider.parseAccessToken(subjectToken)
                .filter(parsed -> parsed.subject() != null && parsed.expiresAt() != null)
                .filter(parsed -> parsed.actorSubject() == null)
                .filter(parsed -> resourcePolicy.audienceContains(parsed.audience(), boundResource))
                .orElseThrow(() -> invalidRequest(INVALID_SUBJECT));

        Set<String> scopes = ScopePolicy.grantAtExchange(subject.scopes(), currentPermissions(subject.subject()),
                client.getScopes());

        Instant issuedAt = Instant.now();
        Instant maxExpiry = issuedAt.plusSeconds(DELEGATED_TOKEN_TTL_SECONDS);
        Instant expiresAt = subject.expiresAt().isBefore(maxExpiry) ? subject.expiresAt() : maxExpiry;
        String accessToken = jwtTokenProvider.generateOAuth2Token(new OAuth2TokenClaims(subject.subject(), scopes,
                audience, client.getClientId(), client.getClientId(), expiresAt));

        String scope = ScopePolicy.toScopeString(scopes);
        log.info("OAuth2 token exchanged | grant_type={} | client_id={} | user={} | scope={} | resource={}",
                GRANT_TYPE, client.getClientId(), subject.subject(), scope, audience);
        return TokenResponse.issuedToken(accessToken, issuedAt, expiresAt, scope, ACCESS_TOKEN_TYPE);
    }

    private static String validateExchangeParameters(HttpServletRequest request) {
        String subjectToken = request.getParameter("subject_token");
        if (subjectToken == null || subjectToken.isBlank()) {
            throw invalidRequest("Missing required parameter: subject_token");
        }
        if (!ACCESS_TOKEN_TYPE.equals(request.getParameter("subject_token_type"))) {
            throw invalidRequest("Unsupported subject_token_type");
        }
        String requestedTokenType = request.getParameter("requested_token_type");
        if (requestedTokenType != null && !ACCESS_TOKEN_TYPE.equals(requestedTokenType)) {
            throw invalidRequest("Unsupported requested_token_type");
        }
        if (request.getParameter("actor_token") != null) {
            throw invalidRequest("actor_token is not supported");
        }
        return subjectToken;
    }

    private Set<String> currentPermissions(String username) {
        try {
            return userDetailsService.loadUserByUsername(username).getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority.startsWith(PERMISSION_PREFIX))
                    .map(authority -> authority.substring(PERMISSION_PREFIX.length()))
                    .collect(Collectors.toSet());
        } catch (UsernameNotFoundException e) {
            throw invalidRequest(INVALID_SUBJECT);
        }
    }

    private static OAuth2RequestException invalidRequest(String description) {
        return new OAuth2RequestException(OAuth2Error.INVALID_REQUEST, description);
    }
}
