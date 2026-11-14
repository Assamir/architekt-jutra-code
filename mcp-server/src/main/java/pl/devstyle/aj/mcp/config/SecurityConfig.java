package pl.devstyle.aj.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.SpringOpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import pl.devstyle.aj.mcp.security.AudienceValidatingIntrospector;
import pl.devstyle.aj.mcp.security.McpAccessDeniedHandler;
import pl.devstyle.aj.mcp.security.McpAuthenticationEntryPoint;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AjProperties.class)
public class SecurityConfig {

    private static final Duration INTROSPECTION_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration INTROSPECTION_READ_TIMEOUT = Duration.ofSeconds(10);

    private final AjProperties properties;

    public SecurityConfig(AjProperties properties) {
        this.properties = properties;
    }

    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(INTROSPECTION_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(INTROSPECTION_READ_TIMEOUT);

        var restTemplate = new RestTemplate(requestFactory);
        restTemplate.getInterceptors().add(new BasicAuthenticationInterceptor(
                urlEncode(properties.oauth().clientId()), urlEncode(properties.oauth().clientSecret())));

        var delegate = new SpringOpaqueTokenIntrospector(properties.introspectionUri(), restTemplate);
        return new AudienceValidatingIntrospector(delegate, properties.mcp().baseUrl());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        var entryPoint = new McpAuthenticationEntryPoint(properties.resourceMetadataUrl());
        var accessDeniedHandler = new McpAccessDeniedHandler(properties.resourceMetadataUrl());

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/.well-known/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/").hasAnyAuthority("SCOPE_mcp:read", "SCOPE_mcp:edit")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .opaqueToken(opaqueToken -> opaqueToken.introspector(introspector))
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(this::describeProtectedResource))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }

    private void describeProtectedResource(OAuth2ProtectedResourceMetadata.Builder metadata) {
        metadata.resource(properties.mcp().baseUrl())
                .authorizationServer(properties.oauth().serverUrl())
                .scope("mcp:read")
                .scope("mcp:edit")
                .tlsClientCertificateBoundAccessTokens(false);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
