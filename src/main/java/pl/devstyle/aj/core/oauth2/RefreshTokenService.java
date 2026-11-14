package pl.devstyle.aj.core.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class RefreshTokenService {

    private static final long TOKEN_EXPIRATION_SECONDS = 86400; // 24 hours
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, RefreshTokenData> refreshTokens = new ConcurrentHashMap<>();

    public String issueToken(String username, String scope, String clientId, String resource) {
        var token = generateToken();
        refreshTokens.put(token, new RefreshTokenData(username, scope, clientId, resource, Instant.now()));
        log.debug("Issued refresh token | client_id={} | user={}", clientId, username);
        return token;
    }

    /**
     * Rotates a refresh token for the client it was issued to. The token is removed only after the client and the
     * optional resource match, so a mismatching caller cannot revoke it for its owner.
     */
    public Optional<RotationResult> consumeAndRotate(String token, String clientId, Optional<String> resource) {
        var data = refreshTokens.get(token);
        if (data == null) {
            log.warn("Refresh token rejected | client_id={} | reason=Token not found or already used", clientId);
            return Optional.empty();
        }
        if (data.createdAt().plusSeconds(TOKEN_EXPIRATION_SECONDS).isBefore(Instant.now())) {
            refreshTokens.remove(token, data);
            log.warn("Refresh token rejected | client_id={} | user={} | reason=Token expired", clientId, data.username());
            return Optional.empty();
        }
        if (!data.clientId().equals(clientId)) {
            log.warn("Refresh token rejected | client_id={} | reason=Token issued to another client", clientId);
            return Optional.empty();
        }
        if (resource.isPresent() && !resource.get().equals(data.resource())) {
            log.warn("Refresh token rejected | client_id={} | resource={} | reason=Resource mismatch",
                    clientId, resource.get());
            return Optional.empty();
        }
        if (!refreshTokens.remove(token, data)) {
            log.warn("Refresh token rejected | client_id={} | reason=Token already used", clientId);
            return Optional.empty();
        }

        var newToken = issueToken(data.username(), data.scope(), data.clientId(), data.resource());
        return Optional.of(new RotationResult(newToken, data));
    }

    @Scheduled(fixedRate = 1_800_000) // every 30 minutes
    void cleanupExpiredTokens() {
        var now = Instant.now();
        int removed = 0;
        var iterator = refreshTokens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().createdAt().plusSeconds(TOKEN_EXPIRATION_SECONDS).isBefore(now)) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up {} expired refresh tokens", removed);
        }
    }

    private String generateToken() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RefreshTokenData(
            String username,
            String scope,
            String clientId,
            String resource,
            Instant createdAt
    ) {}

    public record RotationResult(
            String newToken,
            RefreshTokenData originalData
    ) {}
}
