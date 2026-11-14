package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class ClientAuthenticator {

    private static final String BASIC_PREFIX = "Basic ";
    private static final String CLIENT_ID_PARAM = "client_id";
    private static final String CLIENT_SECRET_PARAM = "client_secret";
    private static final String AUTHENTICATION_FAILED = "Client authentication failed";

    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummySecretHash;

    public ClientAuthenticator(RegisteredClientRepository registeredClientRepository, PasswordEncoder passwordEncoder) {
        this.registeredClientRepository = registeredClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummySecretHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public RegisteredClient authenticateConfidential(HttpServletRequest request) {
        String formClientId = request.getParameter(CLIENT_ID_PARAM);
        String formSecret = request.getParameter(CLIENT_SECRET_PARAM);
        String basicHeader = basicHeader(request);

        String clientId;
        String secret;
        ClientAuthenticationMethod method;
        if (basicHeader != null) {
            if (formSecret != null) {
                throw new OAuth2RequestException(OAuth2Error.INVALID_REQUEST,
                        "Client credentials must be sent using only one authentication method");
            }
            String[] credentials = decodeBasic(basicHeader);
            clientId = credentials[0];
            secret = credentials[1];
            if (formClientId != null && !formClientId.equals(clientId)) {
                throw invalidClient();
            }
            method = ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
        } else if (isPresent(formClientId) && isPresent(formSecret)) {
            clientId = formClientId;
            secret = formSecret;
            method = ClientAuthenticationMethod.CLIENT_SECRET_POST;
        } else {
            throw invalidClient();
        }
        // Record the claimed id up front so a failed authentication (e.g. a secret mismatch) is still attributable.
        OAuth2LogValues.recordClientId(request, clientId);

        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        boolean usable = client != null
                && client.getClientAuthenticationMethods().contains(method)
                && client.getClientSecret() != null;
        // Always run one secret check so response time does not reveal whether the client exists.
        boolean secretMatches = passwordEncoder.matches(secret, usable ? client.getClientSecret() : dummySecretHash);
        if (!usable || !secretMatches) {
            throw invalidClient();
        }
        return client;
    }

    public RegisteredClient identify(HttpServletRequest request) {
        if (basicHeader(request) != null || request.getParameter(CLIENT_SECRET_PARAM) != null) {
            return authenticateConfidential(request);
        }
        String clientId = request.getParameter(CLIENT_ID_PARAM);
        if (!isPresent(clientId)) {
            throw new OAuth2RequestException(OAuth2Error.INVALID_REQUEST, "Missing required parameter: client_id");
        }
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw invalidClient();
        }
        OAuth2LogValues.recordClientId(request, client.getClientId());
        return client;
    }

    private static String basicHeader(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return null;
        }
        return header.substring(BASIC_PREFIX.length()).trim();
    }

    private static String[] decodeBasic(String encodedCredentials) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encodedCredentials), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0) {
                throw invalidClient();
            }
            String clientId = URLDecoder.decode(decoded.substring(0, separator), StandardCharsets.UTF_8);
            String secret = URLDecoder.decode(decoded.substring(separator + 1), StandardCharsets.UTF_8);
            return new String[]{clientId, secret};
        } catch (IllegalArgumentException e) {
            throw invalidClient();
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static OAuth2RequestException invalidClient() {
        return new OAuth2RequestException(OAuth2Error.INVALID_CLIENT, AUTHENTICATION_FAILED);
    }
}
