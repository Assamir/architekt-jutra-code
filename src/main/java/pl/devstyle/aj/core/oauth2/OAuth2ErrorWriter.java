package pl.devstyle.aj.core.oauth2;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

public class OAuth2ErrorWriter {

    private final ObjectMapper objectMapper;

    public OAuth2ErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, OAuth2Error error, String description) throws IOException {
        response.setStatus(statusOf(error));
        if (error == OAuth2Error.INVALID_CLIENT) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"aj\"");
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(OAuth2ErrorResponse.of(error, description)));
        response.getOutputStream().flush();
    }

    private static int statusOf(OAuth2Error error) {
        return switch (error) {
            case INVALID_CLIENT -> HttpServletResponse.SC_UNAUTHORIZED;
            case ACCESS_DENIED -> HttpServletResponse.SC_FORBIDDEN;
            case SERVER_ERROR -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            default -> HttpServletResponse.SC_BAD_REQUEST;
        };
    }
}
