package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.GoogleLoginRequestDTO;
import com.example.FieldFinder.dto.req.LogoutRequestDTO;
import com.example.FieldFinder.dto.req.RefreshTokenRequestDTO;
import com.example.FieldFinder.dto.req.VerifyOtpRequestDTO;
import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.service.AuthService;
import com.example.FieldFinder.service.JwtService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.impl.GoogleAuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/google")
    public ResponseEntity<AuthTokenResponseDTO> loginWithGoogle(@RequestBody GoogleLoginRequestDTO req) {
        AuthTokenResponseDTO tokenResponse = googleAuthService.login(req.getIdToken());
        return ResponseEntity.ok(tokenResponse);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequestDTO req) {
        String newAccessToken = jwtService.refreshAccessToken(req.getRefreshToken());
        return ResponseEntity.ok(Map.of("accessToken", newAccessToken));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> logout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody LogoutRequestDTO req) {

        String accessToken = authHeader.substring(7).trim();

        try {
            Claims claims = jwtService.verifyAccessToken(accessToken);
            long expMillis = claims.getExpiration().getTime();
            long ttlSeconds = (expMillis - Instant.now().toEpochMilli()) / 1000;
            redisService.blacklistJwt(claims.getId(), ttlSeconds);
        } catch (Exception ignored) {
        }

        if (req.getRefreshToken() != null) {
            jwtService.revokeRefreshToken(req.getRefreshToken());
        }

        return ResponseEntity.ok("Đăng xuất thành công.");
    }
}
