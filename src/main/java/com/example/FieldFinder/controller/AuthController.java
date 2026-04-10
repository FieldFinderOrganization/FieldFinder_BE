package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.AuthRequestDTO;
import com.example.FieldFinder.dto.req.FacebookLoginRequestDTO;
import com.example.FieldFinder.dto.req.GoogleLoginRequestDTO;
import com.example.FieldFinder.dto.req.LogoutRequestDTO;
import com.example.FieldFinder.dto.req.RefreshTokenRequestDTO;
import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.VerifyOtpRequestDTO;
import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.AuthService;
import com.example.FieldFinder.service.JwtService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.impl.FacebookAuthService;
import com.example.FieldFinder.service.impl.GoogleAuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final RedisService redisService;
    private final GoogleAuthService googleAuthService;
    private final FacebookAuthService facebookAuthService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/send-otp")
    public ResponseEntity<String> sendOtp(@RequestParam String email) {
        authService.sendLoginOtp(email);
        return ResponseEntity.ok("OTP sent successfully");
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<String> verifyOtp(@RequestBody VerifyOtpRequestDTO req) {
        authService.verifyOtp(req.getEmail(), req.getCode());
        return ResponseEntity.ok("Login success!");
    }

    // -------------------------------------------------------
    // Social Login — Direct API (không qua Firebase SDK)
    // -------------------------------------------------------

    /**
     * Đăng nhập bằng Google Sign-In trực tiếp (không qua Firebase).
     * Mobile dùng Google Sign-In SDK lấy idToken, gửi lên đây.
     * Backend verify offline: chữ ký + audience + expiry.
     * Body: { "idToken": "eyJhb..." }
     */
    @PostMapping("/google")
    public ResponseEntity<AuthTokenResponseDTO> loginWithGoogle(@RequestBody GoogleLoginRequestDTO req) {
        AuthTokenResponseDTO tokenResponse = googleAuthService.login(req.getIdToken());
        return ResponseEntity.ok(tokenResponse);
    }

    /**
     * Đăng nhập bằng Facebook Login trực tiếp (không qua Firebase).
     * Mobile dùng Facebook Login SDK lấy accessToken, gửi lên đây.
     * Backend verify qua Facebook Graph API: debug_token (check app_id) + /me (lấy user info).
     * Body: { "accessToken": "EAAxxxxx..." }
     */
    @PostMapping("/facebook")
    public ResponseEntity<AuthTokenResponseDTO> loginWithFacebook(@RequestBody FacebookLoginRequestDTO req) {
        AuthTokenResponseDTO tokenResponse = facebookAuthService.login(req.getAccessToken());
        return ResponseEntity.ok(tokenResponse);
    }

    // -------------------------------------------------------
    // Email / Password Auth
    // -------------------------------------------------------

    /**
     * Đăng ký tài khoản bằng email & mật khẩu (không qua Firebase).
     * Body: { "name": "...", "email": "...", "phone": "...", "password": "..." }
     */
    @PostMapping("/register")
    public ResponseEntity<AuthTokenResponseDTO> register(@RequestBody UserRequestDTO req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Email đã tồn tại. Vui lòng dùng email khác.");
        }

        User user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(req.getRole() != null ? req.getRole() : User.Role.USER)
                .status(User.Status.ACTIVE)
                .build();

        userRepository.save(user);
        AuthTokenResponseDTO tokenResponse = jwtService.generateTokenPair(user);
        return ResponseEntity.ok(tokenResponse);
    }

    /**
     * Đăng nhập bằng email & mật khẩu.
     * Body: { "email": "...", "password": "..." }
     */
    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponseDTO> loginWithEmail(@RequestBody AuthRequestDTO req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Email hoặc mật khẩu không đúng."));

        if (user.getPassword() == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Email hoặc mật khẩu không đúng.");
        }

        if (user.getStatus() == User.Status.BLOCKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tài khoản của bạn đã bị khóa.");
        }

        AuthTokenResponseDTO tokenResponse = jwtService.generateTokenPair(user);
        return ResponseEntity.ok(tokenResponse);
    }

    // -------------------------------------------------------
    // Token Management
    // -------------------------------------------------------

    /**
     * Cấp Access Token mới từ Refresh Token còn hạn.
     * Client gửi: { "refreshToken": "..." }
     */
    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequestDTO req) {
        String newAccessToken = jwtService.refreshAccessToken(req.getRefreshToken());
        return ResponseEntity.ok(Map.of("accessToken", newAccessToken));
    }

    /**
     * Logout: thu hồi Access Token (blacklist Redis) + xóa Refresh Token khỏi DB.
     * Header: Authorization: Bearer {accessToken}
     * Body:   { "refreshToken": "..." }
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> logout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody LogoutRequestDTO req) {

        String accessToken = authHeader.substring(7).trim();

        // Blacklist access token — tính TTL còn lại
        try {
            Claims claims = jwtService.verifyAccessToken(accessToken);
            long expMillis = claims.getExpiration().getTime();
            long ttlSeconds = (expMillis - Instant.now().toEpochMilli()) / 1000;
            redisService.blacklistJwt(claims.getId(), ttlSeconds);
        } catch (Exception ignored) {
            // Token đã hết hạn thì không cần blacklist
        }

        // Xóa refresh token khỏi DB
        if (req.getRefreshToken() != null) {
            jwtService.revokeRefreshToken(req.getRefreshToken());
        }

        return ResponseEntity.ok("Đăng xuất thành công.");
    }
}
