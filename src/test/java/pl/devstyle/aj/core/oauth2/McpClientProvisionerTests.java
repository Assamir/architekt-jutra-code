package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.transaction.annotation.Transactional;
import pl.devstyle.aj.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class McpClientProvisionerTests {

    private static final String CLIENT_ID = "test-provisioned-client";

    @Autowired
    private DatabaseRegisteredClientRepository registeredClientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void run_withSecret_createsConfidentialExchangeClient() throws Exception {
        provisioner("first-secret").run(new DefaultApplicationArguments());

        var client = registeredClientRepository.findByClientId(CLIENT_ID);
        assertThat(client).isNotNull();
        assertThat(client.getClientName()).isEqualTo("AJ MCP Server");
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.TOKEN_EXCHANGE);
        assertThat(client.getClientAuthenticationMethods()).containsExactlyInAnyOrder(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC, ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(client.getScopes()).containsExactlyInAnyOrder("mcp:read", "mcp:edit");
        assertThat(client.getRedirectUris()).isEmpty();
        assertThat(client.getClientSecret()).isNotEqualTo("first-secret");
        assertThat(passwordEncoder.matches("first-secret", client.getClientSecret())).isTrue();
    }

    @Test
    void run_twiceWithSameSecret_keepsRowIdAndHash() throws Exception {
        provisioner("first-secret").run(new DefaultApplicationArguments());
        var first = registeredClientRepository.findByClientId(CLIENT_ID);

        provisioner("first-secret").run(new DefaultApplicationArguments());
        var second = registeredClientRepository.findByClientId(CLIENT_ID);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getClientSecret()).isEqualTo(first.getClientSecret());
    }

    @Test
    void run_withRotatedSecret_updatesHash() throws Exception {
        provisioner("first-secret").run(new DefaultApplicationArguments());
        var first = registeredClientRepository.findByClientId(CLIENT_ID);

        provisioner("rotated-secret").run(new DefaultApplicationArguments());
        var rotated = registeredClientRepository.findByClientId(CLIENT_ID);

        assertThat(rotated.getId()).isEqualTo(first.getId());
        assertThat(passwordEncoder.matches("rotated-secret", rotated.getClientSecret())).isTrue();
        assertThat(passwordEncoder.matches("first-secret", rotated.getClientSecret())).isFalse();
    }

    @Test
    void run_withBlankSecret_deletesStaleClient() throws Exception {
        provisioner("first-secret").run(new DefaultApplicationArguments());
        assertThat(registeredClientRepository.findByClientId(CLIENT_ID)).isNotNull();

        provisioner("").run(new DefaultApplicationArguments());

        assertThat(registeredClientRepository.findByClientId(CLIENT_ID)).isNull();
    }

    private McpClientProvisioner provisioner(String secret) {
        var properties = new OAuth2Properties("http://localhost:8180", "http://localhost:8181", CLIENT_ID, secret);
        return new McpClientProvisioner(properties, registeredClientRepository, passwordEncoder);
    }
}
