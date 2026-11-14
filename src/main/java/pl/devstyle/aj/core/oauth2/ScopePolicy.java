package pl.devstyle.aj.core.oauth2;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ScopePolicy {

    private static final String MCP_READ = "mcp:read";
    private static final String MCP_EDIT = "mcp:edit";
    public static final List<String> SUPPORTED_SCOPES = List.of(MCP_READ, MCP_EDIT);

    private ScopePolicy() {
    }

    public static Set<String> grantAtAuthorization(String requested, Set<String> permissions, Set<String> clientScopes) {
        Collection<String> requestedScopes = requested == null || requested.isBlank()
                ? clientScopes
                : Arrays.asList(requested.trim().split("\\s+"));
        return grant(requestedScopes, permissions, clientScopes);
    }

    public static Set<String> grantAtExchange(Set<String> subjectScopes, Set<String> permissions, Set<String> clientScopes) {
        if (subjectScopes == null || subjectScopes.isEmpty()) {
            throw new OAuth2RequestException(OAuth2Error.INVALID_SCOPE, "Subject token carries no scopes");
        }
        return grant(subjectScopes, permissions, clientScopes);
    }

    public static String toScopeString(Set<String> scopes) {
        return String.join(" ", orderedScopes(scopes));
    }

    private static Set<String> grant(Collection<String> requested, Set<String> permissions, Set<String> clientScopes) {
        Set<String> permissionScopes = permissionScopes(permissions);

        Set<String> granted = expand(requested);
        granted.retainAll(permissionScopes);
        granted.retainAll(clientScopes);
        if (granted.contains(MCP_EDIT) && permissionScopes.contains(MCP_READ)) {
            granted.add(MCP_READ);
        }
        granted.retainAll(SUPPORTED_SCOPES);

        if (granted.isEmpty()) {
            throw new OAuth2RequestException(OAuth2Error.INVALID_SCOPE, "None of the requested scopes can be granted");
        }
        return orderedScopes(granted);
    }

    private static Set<String> expand(Collection<String> scopes) {
        Set<String> expanded = new LinkedHashSet<>(scopes);
        if (expanded.contains(MCP_EDIT)) {
            expanded.add(MCP_READ);
        }
        return expanded;
    }

    private static Set<String> permissionScopes(Set<String> permissions) {
        Set<String> scopes = new LinkedHashSet<>();
        if (permissions.contains("READ")) {
            scopes.add(MCP_READ);
        }
        if (permissions.contains("EDIT")) {
            scopes.add(MCP_EDIT);
        }
        return scopes;
    }

    private static Set<String> orderedScopes(Set<String> scopes) {
        return SUPPORTED_SCOPES.stream()
                .filter(scopes::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
