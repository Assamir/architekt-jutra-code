package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Log-safe values for OAuth2 endpoint logs. Prefers the client id recorded by client authentication (the claimed id
 * from Basic or form credentials, kept even when authentication fails) or by lookup over the raw {@code client_id}
 * parameter, and replaces control, format and Unicode line/paragraph separator characters in client-supplied values
 * so they cannot forge log lines. Never record secrets here.
 */
final class OAuth2LogValues {

    private static final String CLIENT_ID_ATTRIBUTE = OAuth2LogValues.class.getName() + ".clientId";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final int MAX_LENGTH = 128;

    private OAuth2LogValues() {
    }

    static void recordClientId(HttpServletRequest request, String clientId) {
        request.setAttribute(CLIENT_ID_ATTRIBUTE, clientId);
    }

    static String clientId(HttpServletRequest request) {
        if (request.getAttribute(CLIENT_ID_ATTRIBUTE) instanceof String identified) {
            return sanitize(identified);
        }
        return sanitize(request.getParameter("client_id"));
    }

    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = CONTROL_CHARACTERS.matcher(value).replaceAll("_");
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) + "..." : cleaned;
    }
}
