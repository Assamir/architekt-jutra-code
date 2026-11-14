package pl.devstyle.aj.mcp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

public class McpAccessDeniedHandler implements AccessDeniedHandler {

    private final String resourceMetadataUrl;

    public McpAccessDeniedHandler(String resourceMetadataUrl) {
        this.resourceMetadataUrl = resourceMetadataUrl;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\", scope=\"mcp:read\", resource_metadata=\"" + resourceMetadataUrl + "\"");
    }
}
