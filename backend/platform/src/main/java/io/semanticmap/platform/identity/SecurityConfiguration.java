package io.semanticmap.platform.identity;

import io.semanticmap.platform.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    @Profile("dev")
    SecurityFilterChain development(HttpSecurity http) throws Exception {
        return common(http)
                .addFilterBefore(new DevIdentityFilter(), AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Profile("!dev")
    SecurityFilterChain production(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return roles == null
                    ? List.of()
                    : roles.stream()
                            .filter(r -> List.of(
                                            "ORG_ADMIN", "PROJECT_ADMIN", "ANALYST", "DEVELOPER", "REVIEWER", "VIEWER")
                                    .contains(r))
                            .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .toList();
        });
        return common(http)
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }

    private HttpSecurity common(HttpSecurity http) throws Exception {
        // Stateless bearer-token API; browser credentials are not automatically attached.
        return http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a -> a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                                .requestMatchers("/actuator/health/**", "/api/v1/auth/oauth/**", "/api/v1/git/**")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .addFilterBefore(new CorrelationFilter(), AnonymousAuthenticationFilter.class);
    }

    @Bean
    CorsConfigurationSource cors(
            @Value("${semantic.cors-origins:http://127.0.0.1:5173,http://localhost:5173}") List<String> origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Last-Event-ID", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    static class DevIdentityFilter extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilterAsyncDispatch() {
            return false;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            var auth = new UsernamePasswordAuthenticationToken(
                    new TenantContext.Principal(TenantContext.DEV_ORG, TenantContext.DEV_USER),
                    "",
                    List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                chain.doFilter(req, res);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    static class CorrelationFilter extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilterAsyncDispatch() {
            return false;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            String id = UUID.randomUUID().toString();
            req.setAttribute("correlationId", id);
            res.setHeader("X-Correlation-ID", id);
            MDC.put("correlationId", id);
            try {
                chain.doFilter(req, res);
            } finally {
                MDC.remove("correlationId");
            }
        }
    }
}
