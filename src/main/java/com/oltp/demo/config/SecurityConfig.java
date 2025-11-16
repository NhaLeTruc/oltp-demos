package com.oltp.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import lombok.extern.slf4j.Slf4j;

/**
 * Security configuration for OLTP demo.
 *
 * This is a simplified security setup for demonstration purposes.
 * In production, this should be enhanced with:
 * - OAuth2/JWT authentication
 * - Role-based access control (RBAC)
 * - Rate limiting
 * - CSRF protection for state-changing operations
 *
 * Current setup:
 * - Permits all requests (for easy demonstration access)
 * - Stateless session management
 * - BCrypt password encoding (for future user authentication)
 *
 * @see <a href="constitution.md">VI. Security & Data Protection</a>
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures HTTP security for the application.
     *
     * IMPORTANT: This permissive configuration is ONLY for demonstration purposes.
     * Production deployments must implement proper authentication and authorization.
     *
     * @param http HttpSecurity builder
     * @return configured SecurityFilterChain
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring security filter chain for demo environment");

        http
            // Disable CSRF for demo API (stateless)
            // PRODUCTION: Enable CSRF protection for state-changing operations
            .csrf(AbstractHttpConfigurer::disable)

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Actuator endpoints (health, metrics, prometheus)
                .requestMatchers("/actuator/**").permitAll()

                // Demo API endpoints
                .requestMatchers("/api/demos/**").permitAll()

                // All other requests
                .anyRequest().permitAll()
            )

            // Stateless session management (no HTTP sessions)
            // Aligns with OLTP best practices: no server-side state
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Disable default login/logout pages
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);

        log.warn("Security configured in DEMO MODE - all endpoints are publicly accessible");
        log.warn("DO NOT use this configuration in production without proper authentication");

        return http.build();
    }

    /**
     * Password encoder bean for user credential hashing.
     *
     * Uses BCrypt with default strength (10 rounds) for secure password hashing.
     * BCrypt is chosen for:
     * - Adaptive hashing (configurable work factor)
     * - Built-in salt generation
     * - Industry standard for password storage
     *
     * @return BCrypt password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
