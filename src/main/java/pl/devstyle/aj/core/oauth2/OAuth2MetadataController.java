package pl.devstyle.aj.core.oauth2;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OAuth2MetadataController {

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2Properties properties;

    public OAuth2MetadataController(RegisteredClientRepository registeredClientRepository,
                                    OAuth2Properties properties) {
        this.registeredClientRepository = registeredClientRepository;
        this.properties = properties;
    }

    @GetMapping(value = "/api/oauth2/client-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getClientInfo(@RequestParam("client_id") String clientId) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(Map.of(
                "client_id", client.getClientId(),
                "client_name", client.getClientName() != null ? client.getClientName() : "Unknown Application",
                "scopes", client.getScopes()
        ));
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthorizationServerMetadata getAuthorizationServerMetadata() {
        return AuthorizationServerMetadata.forIssuer(properties.issuer());
    }
}
