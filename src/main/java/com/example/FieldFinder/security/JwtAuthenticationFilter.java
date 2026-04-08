package com.example.FieldFinder.security;

import com.example.FieldFinder.service.JwtService;
import com.example.FieldFinder.service.RedisService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final RedisService redisService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(header);

        if (tryInternalJwt(token, request, response)) {
            filterChain.doFilter(request, response);
            return;
        }

        tryFirebaseToken(token, request, response);

        filterChain.doFilter(request, response);
    }

    private boolean tryInternalJwt(String token, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            Claims claims = jwtService.verifyAccessToken(token);

            String jti = claims.getId();
            if (redisService.isJwtBlacklisted(jti)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\": \"Token đã bị thu hồi. Vui lòng đăng nhập lại.\"}");
                return true;
            }

            String email = claims.getSubject();
            String role = claims.get("role", String.class);

            if (email != null) {
                if (redisService.isUserBanned(email)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\": \"Tài khoản của bạn đã bị khóa!\"}");
                    return true;
                }

                setAuthentication(email, role, request);
            }

            return true;

        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private void tryFirebaseToken(String token, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(token);
            String email = decodedToken.getEmail();

            if (email != null) {
                if (redisService.isUserBanned(email)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\": \"Tài khoản của bạn đã bị khóa!\"}");
                    return;
                }

                String role = redisService.getUserRole(email);
                if (role == null) role = "USER";
                role = role.replace("\"", "").trim();

                setAuthentication(email, role, request);
            }

        } catch (FirebaseAuthException e) {
            System.err.println("❌ Lỗi Firebase Token: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("❌ Lỗi định dạng Token: " + e.getMessage());
        }
    }

    private void setAuthentication(String email, String role, HttpServletRequest request) {
        List<GrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + role)
        );
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                email, null, authorities
        );
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String extractToken(String header) {
        String token = header.substring(7).trim();
        if (token.toLowerCase().startsWith("bearer ")) {
            token = token.substring(7).trim();
        }
        if (token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1).trim();
        }
        return token;
    }
}
