package pl.devstyle.aj.core.oauth2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class McpClientProvisioner implements ApplicationRunner {

    private static final String CLIENT_NAME = "AJ MCP Server";
    private static final Set<String> SCOPES = Set.of("mcp:read", "mcp:edit");

    private final OAuth2Properties properties;
    private final DatabaseRegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var clientId = properties.mcpClientId();
        var secret = properties.mcpClientSecret();

        if (secret == null || secret.isBlank()) {
            if (registeredClientRepository.deleteByClientId(clientId)) {
                log.warn("MCP client secret is blank, removed stale MCP client | client_id={}", clientId);
            } else {
                log.warn("MCP client not provisioned, secret is blank | client_id={}", clientId);
            }
            return;
        }

        var existing = registeredClientRepository.findByClientId(clientId);
        var builder = existing != null
                ? RegisteredClient.from(existing)
                : RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(clientId)
                        .clientIdIssuedAt(Instant.now());

        var storedSecret = existing != null ? existing.getClientSecret() : null;
        var secretUnchanged = storedSecret != null && passwordEncoder.matches(secret, storedSecret);

        var client = builder
                .clientName(CLIENT_NAME)
                .clientSecret(secretUnchanged ? storedSecret : passwordEncoder.encode(secret))
                .clientSecretExpiresAt(null)
                .authorizationGrantTypes(grantTypes -> {
                    grantTypes.clear();
                    grantTypes.add(AuthorizationGrantType.TOKEN_EXCHANGE);
                })
                .clientAuthenticationMethods(methods -> {
                    methods.clear();
                    methods.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                    methods.add(ClientAuthenticationMethod.CLIENT_SECRET_POST);
                })
                .redirectUris(Set::clear)
                .scopes(scopes -> {
                    scopes.clear();
                    scopes.addAll(SCOPES);
                })
                .build();

        registeredClientRepository.save(client);
        log.info("Provisioned MCP client | client_id={}", clientId);
    }
}
