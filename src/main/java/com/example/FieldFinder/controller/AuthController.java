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
import com.example.FieldFinder.service.impl.AuthServiceImpl;
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

    @PostMapping("/send-activation-email")
    public ResponseEntity<String> sendActivationEmail(@RequestParam String email) {
        authService.sendActivationEmail(email);
        return ResponseEntity.ok("Activation email sent");
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

    @PostMapping("/facebook")
    public ResponseEntity<AuthTokenResponseDTO> loginWithFacebook(@RequestBody FacebookLoginRequestDTO req) {
        AuthTokenResponseDTO tokenResponse = facebookAuthService.login(req.getAccessToken());
        return ResponseEntity.ok(tokenResponse);
    }

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

        user.setLastLoginAt(new java.util.Date());
        userRepository.save(user);
        AuthTokenResponseDTO tokenResponse = jwtService.generateTokenPair(user);
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

    @PostMapping("/send-reset-otp")
    public ResponseEntity<String> sendResetOtp(@RequestParam String email) {
        ((AuthServiceImpl) authService).sendResetPasswordOtp(email);
        return ResponseEntity.ok("Mã đặt lại mật khẩu đã được gửi qua email");
    }
}