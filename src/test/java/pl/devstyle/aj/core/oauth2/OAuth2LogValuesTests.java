package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LogValuesTests {

    @Test
    void sanitize_lineBreaksAndControlCharacters_areReplaced() {
        assertThat(OAuth2LogValues.sanitize("evil\r\nINFO forged | user=admin\t\u0000"))
                .isEqualTo("evil__INFO forged | user=admin__")
                .doesNotContain("\n", "\r");
    }

    @Test
    void sanitize_unicodeLineSeparatorsAndFormatCharacters_areReplaced() {
        assertThat(OAuth2LogValues.sanitize("ab c d‎ef"))
                .isEqualTo("a_b_c_d_e_f");
    }

    @Test
    void sanitize_overlongValue_isTruncated() {
        assertThat(OAuth2LogValues.sanitize("a".repeat(500))).hasSizeLessThan(200);
    }

    @Test
    void clientId_withoutIdentifiedClient_returnsSanitizedFormParameter() {
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("client_id", "client\nforged");

        assertThat(OAuth2LogValues.clientId(request)).isEqualTo("client_forged");
    }

    @Test
    void clientId_identifiedClient_takesPrecedenceOverFormParameter() {
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("client_id", "claimed-client");
        OAuth2LogValues.recordClientId(request, "identified-client");

        assertThat(OAuth2LogValues.clientId(request)).isEqualTo("identified-client");
    }
}
