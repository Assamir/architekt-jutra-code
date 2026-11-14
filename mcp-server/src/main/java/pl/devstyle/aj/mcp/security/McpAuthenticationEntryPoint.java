package pl.devstyle.aj.mcp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String resourceMetadataUrl;

    public McpAuthenticationEntryPoint(String resourceMetadataUrl) {
        this.resourceMetadataUrl = resourceMetadataUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        var challenge = "Bearer resource_metadata=\"" + resourceMetadataUrl + "\", scope=\"mcp:read mcp:edit\"";
        if (authException instanceof OAuth2AuthenticationException) {
            challenge += ", error=\"invalid_token\"";
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
    }
}
