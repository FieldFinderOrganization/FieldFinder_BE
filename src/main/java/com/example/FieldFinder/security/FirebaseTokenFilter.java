package com.example.FieldFinder.security;

import com.example.FieldFinder.service.RedisService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
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
import java.util.List;

@Component
@RequiredArgsConstructor
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private final RedisService redisService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            // 1. Cắt bỏ 7 ký tự đầu tiên ("Bearer ") và xóa khoảng trắng thừa 2 đầu
            String idToken = header.substring(7).trim();

            // 2. ĐỀ PHÒNG 1: Nếu user lỡ tay dán thêm chữ Bearer vào Swagger -> Xóa tiếp
            if (idToken.toLowerCase().startsWith("bearer ")) {
                idToken = idToken.substring(7).trim();
            }

            // 3. ĐỀ PHÒNG 2: Nếu user copy từ F12 bị dính dấu nháy kép ("") ở 2 đầu -> Xóa nháy kép
            if (idToken.startsWith("\"") && idToken.endsWith("\"")) {
                idToken = idToken.substring(1, idToken.length() - 1).trim();
            }

            try {
                FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
                String email = decodedToken.getEmail();

                if (email != null) {
                    if (redisService.isUserBanned(email)) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"error\": \"Tài khoản của bạn đã bị khóa!\"}");
                        return;
                    }

                    String userRole = redisService.getUserRole(email);
                    if (userRole == null) {
                        userRole = "USER";
                    }

                    // Loại bỏ dấu nháy kép do Redis tự sinh ra
                    userRole = userRole.replace("\"", "").trim();

                    List<GrantedAuthority> authorities = Collections.singletonList(
                            new SimpleGrantedAuthority("ROLE_" + userRole)
                    );

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            email,
                            null,
                            authorities
                    );

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }

            } catch (FirebaseAuthException e) {
                // In ra console để biết chính xác lỗi gì
                System.err.println("❌ Lỗi Firebase Token: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.err.println("❌ Lỗi định dạng Token: " + e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}