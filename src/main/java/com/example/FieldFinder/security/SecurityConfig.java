package com.example.FieldFinder.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()

                        .requestMatchers("/ws/**", "/api/chat/**").permitAll()

                        .requestMatchers("/api/payment/**").permitAll()

                        .requestMatchers(
                                "/api/users/register",
                                "/api/users/login",
                                "/api/users/login-social",
                                "/api/users/forgot-password",
                                "/api/users/reset-password",
                                "/api/users/reset-password-otp",
                                "/api/auth/send-activation-email",
                                "/api/users",
                                "/api/auth/send-otp",
                                "/api/auth/verify-otp",
                                "/api/auth/refresh-token",
                                "/api/auth/google",
                                "/api/auth/facebook",
                                // PassKey login — public (chưa có token)
                                "/api/auth/passkey/login/start",
                                "/api/auth/passkey/login/finish"
                                // PassKey register — PROTECTED (cần token, không permit ở đây)
                        ).permitAll()

                        // .well-known cho Android (Digital Asset Links) và iOS (AASA)
                        // Cần khi deploy production để mobile verify RP ID
                        .requestMatchers("/.well-known/**").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/pitches/**", "/api/categories/**").permitAll()

                        .anyRequest().authenticated()
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
