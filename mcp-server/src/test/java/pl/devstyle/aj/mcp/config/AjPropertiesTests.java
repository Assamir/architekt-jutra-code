package pl.devstyle.aj.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AjPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "aj.backend.url=http://localhost:8180",
                    "aj.oauth.server-url=http://localhost:8180",
                    "aj.oauth.client-id=aj-mcp-server",
                    "aj.oauth.client-secret=s3cr3t-value",
                    "aj.mcp.base-url=http://localhost:8181");

    @Test
    void context_withoutClientSecret_failsToStart() {
        contextRunner
                .withPropertyValues("aj.oauth.client-secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("AJ_OAUTH_CLIENT_SECRET");
                });
    }

    @Test
    void context_withBlankBaseUrl_failsToStart() {
        contextRunner
                .withPropertyValues("aj.mcp.base-url= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("aj.mcp.base-url");
                });
    }

    @Test
    void context_withBlankBackendUrl_failsToStart() {
        contextRunner
                .withPropertyValues("aj.backend.url= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("aj.backend.url");
                });
    }

    @Test
    void backendUrl_withTrailingSlash_isStripped() {
        contextRunner
                .withPropertyValues("aj.backend.url=http://localhost:8180/")
                .run(context -> assertThat(context.getBean(AjProperties.class).backend().url())
                        .isEqualTo("http://localhost:8180"));
    }

    @Test
    void derivedUris_followServerUrl() {
        contextRunner
                .withPropertyValues(
                        "aj.oauth.server-url=http://auth.example:9000",
                        "aj.mcp.base-url=http://mcp.example:9001")
                .run(context -> {
                    var properties = context.getBean(AjProperties.class);

                    assertThat(properties.introspectionUri()).isEqualTo("http://auth.example:9000/oauth2/introspect");
                    assertThat(properties.tokenUri()).isEqualTo("http://auth.example:9000/oauth2/token");
                    assertThat(properties.backendResource()).isEqualTo("http://auth.example:9000/api");
                    assertThat(properties.resourceMetadataUrl())
                            .isEqualTo("http://mcp.example:9001/.well-known/oauth-protected-resource");
                });
    }

    @Test
    void derivedUris_trailingSlashServerUrl_haveNoDoubleSlash() {
        contextRunner
                .withPropertyValues(
                        "aj.oauth.server-url=http://localhost:8180/",
                        "aj.mcp.base-url=http://localhost:8181/")
                .run(context -> {
                    var properties = context.getBean(AjProperties.class);

                    assertThat(properties.oauth().serverUrl()).isEqualTo("http://localhost:8180");
                    assertThat(properties.mcp().baseUrl()).isEqualTo("http://localhost:8181");
                    assertThat(properties.introspectionUri()).isEqualTo("http://localhost:8180/oauth2/introspect");
                    assertThat(properties.tokenUri()).isEqualTo("http://localhost:8180/oauth2/token");
                    assertThat(properties.backendResource()).isEqualTo("http://localhost:8180/api");
                    assertThat(properties.resourceMetadataUrl())
                            .isEqualTo("http://localhost:8181/.well-known/oauth-protected-resource");
                });
    }

    @Test
    void toString_masksClientSecret() {
        contextRunner.run(context -> {
            var properties = context.getBean(AjProperties.class);

            assertThat(properties.oauth().toString())
                    .doesNotContain("s3cr3t-value")
                    .contains("***");
            assertThat(properties.toString()).doesNotContain("s3cr3t-value");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AjProperties.class)
    static class PropertiesConfig {
    }
}
