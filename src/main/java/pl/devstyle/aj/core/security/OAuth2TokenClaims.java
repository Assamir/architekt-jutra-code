package pl.devstyle.aj.core.security;

import java.time.Instant;
import java.util.Set;

/**
 * Claims of an OAuth2 access token ({@code typ: at+jwt}). {@code actorSubject} is null unless the token
 * was obtained through token exchange, where it becomes the {@code act.sub} claim.
 */
public record OAuth2TokenClaims(String subject, Set<String> scopes, String audience, String clientId,
                                String actorSubject, Instant expiresAt) {
}
