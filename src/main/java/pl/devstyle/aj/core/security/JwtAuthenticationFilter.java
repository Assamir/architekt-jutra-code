package pl.devstyle.aj.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var token = extractToken(request);

        if (token != null) {
            jwtTokenProvider.parseApiToken(token).ifPresent(parsed -> {
                var authorities = grantedRights(parsed).stream()
                        .map(right -> new SimpleGrantedAuthority("PERMISSION_" + right))
                        .toList();

                var authentication = new UsernamePasswordAuthenticationToken(
                        parsed.subject(), null, authorities);
                authentication.setDetails(parsed);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        filterChain.doFilter(request, response);
    }

    private static Set<String> grantedRights(JwtTokenProvider.ParsedToken parsed) {
        if (parsed.isSessionToken()) {
            return parsed.permissions();
        }
        return parsed.clientId() != null ? parsed.scopes() : Set.of();
    }

    private String extractToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // Support form-based token for OAuth2 authorize endpoint (native form POST)
        var formToken = request.getParameter("_token");
        if (formToken != null && !formToken.isBlank()) {
            return formToken;
        }
        return null;
    }
}
