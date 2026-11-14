package pl.devstyle.aj.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.devstyle.aj.core.oauth2.OAuth2Properties;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider {

    private static final String ACCESS_TOKEN_TYPE = "at+jwt";

    private final SecretKey secretKey;
    private final long expirationMs;
    private final OAuth2Properties properties;

    public JwtTokenProvider(@Value("${app.jwt.secret}") String secret,
                            @Value("${app.jwt.expiration-ms}") long expirationMs,
                            OAuth2Properties properties) {
        this.secretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expirationMs = expirationMs;
        this.properties = properties;
    }

    public String generateToken(String username, Set<String> permissions) {
        return generateTokenWithExpiration(username, permissions, expirationMs);
    }

    public String generateTokenWithExpiration(String username, Set<String> permissions, long expirationMs) {
        var now = new Date();
        var expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(username)
                .audience().add(properties.apiAudience()).and()
                .claim("permissions", List.copyOf(permissions))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public String generateOAuth2Token(OAuth2TokenClaims claims) {
        var builder = Jwts.builder()
                .header().type(ACCESS_TOKEN_TYPE).and()
                .issuer(properties.issuer())
                .subject(claims.subject())
                .audience().add(claims.audience()).and()
                .claim("client_id", claims.clientId())
                .claim("scopes", List.copyOf(claims.scopes()))
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(Date.from(claims.expiresAt()));
        if (claims.actorSubject() != null) {
            builder.claim("act", Map.of("sub", claims.actorSubject()));
        }
        return builder.signWith(secretKey).compact();
    }

    public record ParsedToken(String subject, Set<String> audience, Set<String> permissions, Set<String> scopes,
                              String clientId, String actorSubject, String type, String jti,
                              Instant issuedAt, Instant expiresAt) {

        public boolean isSessionToken() {
            return clientId == null && actorSubject == null;
        }
    }

    /**
     * Host API gate: accepts only tokens issued by this host for the API audience (T0 session, T2 delegated).
     */
    public Optional<ParsedToken> parseApiToken(String token) {
        return parse(token).filter(parsed -> parsed.audience().contains(properties.apiAudience()));
    }

    /**
     * Authorization-server mode: accepts any OAuth2 access token ({@code typ: at+jwt}) issued by this host,
     * regardless of audience. Callers check the audience themselves.
     */
    public Optional<ParsedToken> parseAccessToken(String token) {
        return parse(token).filter(parsed -> ACCESS_TOKEN_TYPE.equalsIgnoreCase(parsed.type()));
    }

    private Optional<ParsedToken> parse(String token) {
        try {
            var jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();
            return Optional.of(new ParsedToken(
                    claims.getSubject(),
                    claims.getAudience() != null ? Set.copyOf(claims.getAudience()) : Set.of(),
                    stringSet(claims.get("permissions")),
                    stringSet(claims.get("scopes")),
                    claims.get("client_id", String.class),
                    actorSubject(claims.get("act")),
                    jws.getHeader().getType(),
                    claims.getId(),
                    toInstant(claims.getIssuedAt()),
                    toInstant(claims.getExpiration())));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Set<String> stringSet(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    private static String actorSubject(Object actClaim) {
        if (actClaim instanceof Map<?, ?> act && act.get("sub") instanceof String sub) {
            return sub;
        }
        return null;
    }

    private static Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
