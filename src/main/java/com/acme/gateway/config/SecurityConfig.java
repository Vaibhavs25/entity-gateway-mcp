package com.acme.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Security for the MCP endpoint.
 *
 * Layers (defense in depth, architecture doc §5):
 *   1. Origin allow-list on /mcp — the MCP spec's mandatory DNS-rebinding
 *      defense for HTTP transports.
 *   2. OAuth2 Resource Server: issuer-validated JWT, audience "entity-gateway".
 *   3. Claim "roles" → ROLE_AGENT_READER / ROLE_AGENT_WRITER / ROLE_AGENT_OPERATOR.
 *   4. @PreAuthorize on every tool method (enabled via @EnableMethodSecurity).
 *
 * Stateless sessions: the MCP session concept lives in the Mcp-Session-Id
 * header handled by the transport, NOT in servlet sessions — nothing to fixate.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Set<String> AGENT_ROLES =
            Set.of("AGENT_READER", "AGENT_WRITER", "AGENT_OPERATOR");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   OriginValidationFilter originFilter) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp/**"))   // bearer-token API, no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(originFilter, BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/actuator/**").hasRole("PLATFORM_OPS")
                    .requestMatchers(HttpMethod.POST,   "/mcp/**").authenticated()
                    .requestMatchers(HttpMethod.GET,    "/mcp/**").authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/mcp/**").authenticated()
                    .anyRequest().denyAll())                          // default-deny everything else
            .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(agentJwtConverter())));
        return http.build();
    }

    /** Maps the IdP's "roles" claim to Spring authorities, keeping only agent roles. */
    private Converter<Jwt, AbstractAuthenticationToken> agentJwtConverter() {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> authorities = roles == null ? List.of() :
                    roles.stream()
                         .filter(AGENT_ROLES::contains)
                         .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                         .toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    /**
     * MCP-spec DNS-rebinding defense: reject /mcp requests whose Origin header
     * is present but not allow-listed. (Absent Origin = non-browser agent
     * client, which is the normal case and is allowed; the JWT still gates it.)
     */
    @Bean
    public OriginValidationFilter originValidationFilter(
            @Value("${gateway.security.allowed-origins}") List<String> allowedOrigins) {
        return new OriginValidationFilter(Set.copyOf(allowedOrigins));
    }

    public static final class OriginValidationFilter extends OncePerRequestFilter {

        private final Set<String> allowed;

        OriginValidationFilter(Set<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/mcp");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String origin = request.getHeader("Origin");
            if (origin != null && !allowed.contains(origin)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
