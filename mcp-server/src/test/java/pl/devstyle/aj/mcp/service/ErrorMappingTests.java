package pl.devstyle.aj.mcp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import pl.devstyle.aj.mcp.client.AjApiClient;
import pl.devstyle.aj.mcp.client.TokenExchangeClient;
import pl.devstyle.aj.mcp.exception.McpToolException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class ErrorMappingTests {

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryService categoryService;

    @MockitoBean
    private AjApiClient ajApiClient;

    @MockitoBean
    private TokenExchangeClient tokenExchangeClient;

    @BeforeEach
    void stubTokenExchange() {
        when(tokenExchangeClient.exchange(any())).thenReturn("exchanged-token");
    }

    @Test
    void listProducts_whenBackendThrowsValidationError_propagatesMcpToolException() {
        when(ajApiClient.listProducts(any(), any()))
                .thenThrow(McpToolException.validationError("Invalid search parameter"));

        assertThatThrownBy(() -> productService.listProducts(caller(), "bad"))
                .isInstanceOf(McpToolException.class)
                .satisfies(ex -> {
                    var mcpEx = (McpToolException) ex;
                    assertThat(mcpEx.getErrorType()).isEqualTo(McpToolException.ErrorType.VALIDATION);
                    assertThat(mcpEx.getMessage()).isEqualTo("Invalid search parameter");
                });
    }

    @Test
    void listProducts_whenBackendThrowsNotFound_propagatesMcpToolException() {
        when(ajApiClient.listProducts(any(), any()))
                .thenThrow(McpToolException.notFound("Resource not found"));

        assertThatThrownBy(() -> productService.listProducts(caller(), null))
                .isInstanceOf(McpToolException.class)
                .satisfies(ex -> {
                    var mcpEx = (McpToolException) ex;
                    assertThat(mcpEx.getErrorType()).isEqualTo(McpToolException.ErrorType.NOT_FOUND);
                });
    }

    @Test
    void addProduct_whenBackendThrowsApiError_propagatesMcpToolException() {
        when(ajApiClient.createProduct(any(), any()))
                .thenThrow(McpToolException.apiError("Backend error (status 500). Please retry."));

        assertThatThrownBy(() -> productService.addProduct(caller(),
                java.util.Map.of("name", "Test", "price", 10, "sku", "T-1", "categoryId", 1)))
                .isInstanceOf(McpToolException.class)
                .satisfies(ex -> {
                    var mcpEx = (McpToolException) ex;
                    assertThat(mcpEx.getErrorType()).isEqualTo(McpToolException.ErrorType.API_ERROR);
                    assertThat(mcpEx.getMessage()).contains("retry");
                });
    }

    @Test
    void listProducts_whenUnexpectedException_wrapsAsApiError() {
        when(ajApiClient.listProducts(any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> productService.listProducts(caller(), null))
                .isInstanceOf(McpToolException.class)
                .satisfies(ex -> {
                    var mcpEx = (McpToolException) ex;
                    assertThat(mcpEx.getErrorType()).isEqualTo(McpToolException.ErrorType.API_ERROR);
                    assertThat(mcpEx.getMessage()).contains("Failed to list products");
                    assertThat(mcpEx.getMessage()).contains("Connection refused");
                });
    }

    @Test
    void listCategories_whenUnexpectedException_wrapsAsApiError() {
        when(ajApiClient.listCategories(any()))
                .thenThrow(new RuntimeException("Timeout"));

        assertThatThrownBy(() -> categoryService.listCategories(caller()))
                .isInstanceOf(McpToolException.class)
                .satisfies(ex -> {
                    var mcpEx = (McpToolException) ex;
                    assertThat(mcpEx.getErrorType()).isEqualTo(McpToolException.ErrorType.API_ERROR);
                    assertThat(mcpEx.getMessage()).contains("Failed to list categories");
                });
    }

    private static BearerTokenAuthentication caller() {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_mcp:read"), new SimpleGrantedAuthority("SCOPE_mcp:edit"));
        var principal = new OAuth2IntrospectionAuthenticatedPrincipal(Map.<String, Object>of("sub", "admin"), authorities);
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-token",
                Instant.now(), Instant.now().plusSeconds(600));
        return new BearerTokenAuthentication(principal, token, authorities);
    }
}
