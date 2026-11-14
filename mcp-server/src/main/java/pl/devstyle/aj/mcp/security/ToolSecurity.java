package pl.devstyle.aj.mcp.security;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import pl.devstyle.aj.mcp.exception.McpToolException;

/**
 * Per-tool authorization: the validated caller travels from the servlet thread to the tool handler
 * inside the {@link McpTransportContext}, and each tool checks its own scope before any token exchange.
 */
public final class ToolSecurity {

    public static final String AUTHENTICATION_KEY = "authentication";

    private ToolSecurity() {
    }

    public static BearerTokenAuthentication principal(McpTransportContext context) {
        if (context != null && context.get(AUTHENTICATION_KEY) instanceof BearerTokenAuthentication caller) {
            return caller;
        }
        throw McpToolException.apiError("Not authenticated");
    }

    /**
     * @return the caller's subject token, to be exchanged for a backend token
     */
    public static String requireScope(BearerTokenAuthentication caller, String scope) {
        var authority = "SCOPE_" + scope;
        boolean granted = caller.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
        if (!granted) {
            throw McpToolException.insufficientScope(scope);
        }
        return caller.getToken().getTokenValue();
    }
}
