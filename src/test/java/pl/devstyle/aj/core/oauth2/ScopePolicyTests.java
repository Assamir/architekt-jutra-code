package pl.devstyle.aj.core.oauth2;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopePolicyTests {

    private static final Set<String> ALL_CLIENT_SCOPES = Set.of("mcp:read", "mcp:edit");

    @Test
    void grant_editorRequestingEdit_returnsReadAndEdit() {
        var granted = ScopePolicy.grantAtAuthorization("mcp:edit", Set.of("READ", "EDIT"), ALL_CLIENT_SCOPES);

        assertThat(granted).containsExactlyInAnyOrder("mcp:read", "mcp:edit");
        assertThat(ScopePolicy.toScopeString(granted)).isEqualTo("mcp:read mcp:edit");
    }

    @Test
    void grant_viewerRequestingEdit_returnsReadOnly() {
        var granted = ScopePolicy.grantAtAuthorization("mcp:read mcp:edit", Set.of("READ"), ALL_CLIENT_SCOPES);

        assertThat(granted).containsExactly("mcp:read");
    }

    @Test
    void grant_editOnlyUser_returnsEditOnly() {
        var granted = ScopePolicy.grantAtAuthorization("mcp:edit", Set.of("EDIT"), ALL_CLIENT_SCOPES);

        assertThat(granted).containsExactly("mcp:edit");
    }

    @Test
    void grant_editOnlyClientForReadUser_reExpandsToReadAndEdit() {
        var granted = ScopePolicy.grantAtAuthorization("mcp:edit", Set.of("READ", "EDIT"), Set.of("mcp:edit"));

        assertThat(granted).containsExactlyInAnyOrder("mcp:read", "mcp:edit");
    }

    @Test
    void grant_unknownScope_isDropped() {
        var granted = ScopePolicy.grantAtAuthorization("mcp:read admin:all", Set.of("READ", "EDIT"),
                Set.of("mcp:read", "mcp:edit", "admin:all"));

        assertThat(granted).containsExactly("mcp:read");
    }

    @Test
    void grant_emptyIntersection_throwsInvalidScope() {
        assertThatThrownBy(() -> ScopePolicy.grantAtAuthorization("mcp:read", Set.of("PLUGIN_MANAGEMENT"), ALL_CLIENT_SCOPES))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_SCOPE));
    }

    @Test
    void grantAtAuthorization_missingRequested_usesClientScopes() {
        assertThat(ScopePolicy.grantAtAuthorization(null, Set.of("READ"), Set.of("mcp:read")))
                .containsExactly("mcp:read");
        assertThat(ScopePolicy.grantAtAuthorization("  ", Set.of("READ", "EDIT"), ALL_CLIENT_SCOPES))
                .containsExactlyInAnyOrder("mcp:read", "mcp:edit");
    }

    @Test
    void grantAtExchange_emptySubjectScopes_throwsInvalidScope() {
        assertThatThrownBy(() -> ScopePolicy.grantAtExchange(Set.of(), Set.of("READ", "EDIT"), ALL_CLIENT_SCOPES))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_SCOPE));
        assertThatThrownBy(() -> ScopePolicy.grantAtExchange(null, Set.of("READ", "EDIT"), ALL_CLIENT_SCOPES))
                .isInstanceOfSatisfying(OAuth2RequestException.class,
                        e -> assertThat(e.getError()).isEqualTo(OAuth2Error.INVALID_SCOPE));
    }
}
