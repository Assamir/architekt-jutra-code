package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAuthenticatorTests {

    private static final String BASIC_CLIENT_ID = "basic-client";
    private static final String POST_CLIENT_ID = "post-client";
    private static final String ENCODED_CLIENT_ID = "mcp client";
    private static final String SECRET = "s3cret";
    private static final String SPECIAL_SECRET = "s3c:r+t%";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final ClientAuthenticator authenticator = new ClientAuthenticator(new InMemoryRegisteredClientRepository(
            client(BASIC_CLIENT_ID, SECRET, ClientAuthenticationMethod.CLIENT_SECRET_BASIC),
            client(POST_CLIENT_ID, SECRET, ClientAuthenticationMethod.CLIENT_SECRET_POST),
            client(ENCODED_CLIENT_ID, SPECIAL_SECRET, ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
    ), passwordEncoder);

    @Test
    void authenticateConfidential_basic_returnsClient() {
        var request = basicRequest(BASIC_CLIENT_ID, SECRET);

        assertThat(authenticator.authenticateConfidential(request).getClientId()).isEqualTo(BASIC_CLIENT_ID);
    }

    @Test
    void authenticateConfidential_urlEncodedBasic_decodesCredentials() {
        var request = basicRequest(urlEncode(ENCODED_CLIENT_ID), urlEncode(SPECIAL_SECRET));

        assertThat(authenticator.authenticateConfidential(request).getClientId()).isEqualTo(ENCODED_CLIENT_ID);
    }

    @Test
    void authenticateConfidential_post_returnsClient() {
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("client_id", POST_CLIENT_ID);
        request.addParameter("client_secret", SECRET);

        assertThat(authenticator.authenticateConfidential(request).getClientId()).isEqualTo(POST_CLIENT_ID);
    }

    @Test
    void authenticateConfidential_basicAndFormSecret_throwsInvalidRequest() {
        var request = basicRequest(BASIC_CLIENT_ID, SECRET);
        request.addParameter("client_secret", SECRET);

        assertError(request, OAuth2Error.INVALID_REQUEST);
    }

    @Test
    void authenticateConfidential_basicWithMatchingFormClientId_returnsClient() {
        var request = basicRequest(BASIC_CLIENT_ID, SECRET);
        request.addParameter("client_id", BASIC_CLIENT_ID);

        assertThat(authenticator.authenticateConfidential(request).getClientId()).isEqualTo(BASIC_CLIENT_ID);
    }

    @Test
    void authenticateConfidential_basicWithDifferentFormClientId_throwsInvalidClient() {
        var request = basicRequest(BASIC_CLIENT_ID, SECRET);
        request.addParameter("client_id", POST_CLIENT_ID);

        assertError(request, OAuth2Error.INVALID_CLIENT);
    }

    @Test
    void authenticateConfidential_wrongSecret_throwsInvalidClient() {
        assertError(basicRequest(BASIC_CLIENT_ID, "wrong"), OAuth2Error.INVALID_CLIENT);

        var postRequest = new MockHttpServletRequest("POST", "/oauth2/token");
        postRequest.addParameter("client_id", POST_CLIENT_ID);
        postRequest.addParameter("client_secret", "wrong");
        assertError(postRequest, OAuth2Error.INVALID_CLIENT);
    }

    @Test
    void authenticateConfidential_unknownClient_throwsInvalidClient() {
        assertError(basicRequest("unknown-client", SECRET), OAuth2Error.INVALID_CLIENT);
    }

    @Test
    void authenticateConfidential_unknownClient_stillVerifiesSecretAgainstDummyHash() {
        var countingEncoder = new CountingPasswordEncoder(passwordEncoder);
        var countingAuthenticator = new ClientAuthenticator(new InMemoryRegisteredClientRepository(
                client(BASIC_CLIENT_ID, SECRET, ClientAuthenticationMethod.CLIENT_SECRET_BASIC)), countingEncoder);

        assertThatThrownBy(() -> countingAuthenticator.authenticateConfidential(basicRequest("unknown-client", SECRET)))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_CLIENT));
        assertThat(countingEncoder.matchesCalls).isEqualTo(1);
    }

    @Test
    void authenticateConfidential_unregisteredMethod_stillVerifiesSecretOnce() {
        var countingEncoder = new CountingPasswordEncoder(passwordEncoder);
        var countingAuthenticator = new ClientAuthenticator(new InMemoryRegisteredClientRepository(
                client(POST_CLIENT_ID, SECRET, ClientAuthenticationMethod.CLIENT_SECRET_POST)), countingEncoder);

        assertThatThrownBy(() -> countingAuthenticator.authenticateConfidential(basicRequest(POST_CLIENT_ID, SECRET)))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_CLIENT));
        assertThat(countingEncoder.matchesCalls).isEqualTo(1);
    }

    @Test
    void authenticateConfidential_success_recordsAuthenticatedClientIdForLogging() {
        var request = basicRequest(BASIC_CLIENT_ID, SECRET);

        authenticator.authenticateConfidential(request);

        assertThat(OAuth2LogValues.clientId(request)).isEqualTo(BASIC_CLIENT_ID);
    }

    @Test
    void authenticateConfidential_basicWithWrongSecret_recordsClaimedClientIdForLogging() {
        var request = basicRequest(BASIC_CLIENT_ID, "wrong-secret");

        assertThatThrownBy(() -> authenticator.authenticateConfidential(request))
                .isInstanceOf(OAuth2RequestException.class);

        assertThat(OAuth2LogValues.clientId(request)).isEqualTo(BASIC_CLIENT_ID);
    }

    private void assertError(MockHttpServletRequest request, OAuth2Error expected) {
        assertThatThrownBy(() -> authenticator.authenticateConfidential(request))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(expected));
    }

    private static MockHttpServletRequest basicRequest(String clientId, String secret) {
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        var credentials = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Basic " + credentials);
        return request;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RegisteredClient client(String clientId, String secret, ClientAuthenticationMethod method) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(method)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:3000/callback")
                .scope("mcp:read")
                .build();
    }

    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate;
        private int matchesCalls;

        private CountingPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls++;
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
