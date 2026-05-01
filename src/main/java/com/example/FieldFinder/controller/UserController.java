package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.AuthService;
import com.example.FieldFinder.service.JwtService;
import com.example.FieldFinder.service.UserService;
import com.example.FieldFinder.service.impl.AuthServiceImpl;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthService authService;

    public UserController(UserService userService, JwtService jwtService, UserRepository userRepository, AuthService authService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> registerUser(@RequestBody UserRequestDTO userRequestDTO) {
        UserResponseDTO createdUser = userService.createUser(userRequestDTO);
        return ResponseEntity.ok(createdUser);
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
            UserResponseDTO loggedInUser = userService.loginUser(decodedToken);

            User user = userRepository.findByEmail(loggedInUser.getEmail())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

            AuthTokenResponseDTO tokenResponse = jwtService.generateTokenPair(user);
            return ResponseEntity.ok(tokenResponse);

        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Firebase ID token không hợp lệ", "details", e.getMessage()));
        }
    }

    @PostMapping("/login-social")
    public ResponseEntity<?> loginWithSocial(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
            UserResponseDTO loggedInUser = userService.loginWithFirebase(decodedToken);

            User user = userRepository.findByEmail(loggedInUser.getEmail())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

            AuthTokenResponseDTO tokenResponse = jwtService.generateTokenPair(user);
            return ResponseEntity.ok(tokenResponse);

        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Firebase ID token không hợp lệ", "details", e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestParam String email) {
        userService.sendPasswordResetEmail(email);
        return ResponseEntity.ok("Email khôi phục mật khẩu đã được gửi.");
    }

    @PostMapping("/forgot-password-otp")
    public ResponseEntity<String> forgotPasswordOtp(@RequestParam String email) {
        userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Email không tồn tại trong hệ thống."));

        if (authService instanceof AuthServiceImpl) {
            ((AuthServiceImpl) authService).sendResetPasswordOtp(email);
        }

        return ResponseEntity.ok("Mã OTP đặt lại mật khẩu đã được gửi.");
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(
            @RequestParam String token,
            @RequestParam String newPassword) {
        userService.resetPassword(token, newPassword);
        return ResponseEntity.ok("Cập nhật mật khẩu thành công.");
    }

    @PostMapping("/reset-password-otp")
    public ResponseEntity<String> resetPasswordWithOtp(
            @RequestParam String email,
            @RequestParam String newPassword) {
        userService.resetPasswordWithOtp(email, newPassword);
        return ResponseEntity.ok("Cập nhật mật khẩu thành công.");
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN') or @securityChecker.isOwner(#userId, authentication.name)")
    public ResponseEntity<UserResponseDTO> updateUser(
            @PathVariable UUID userId,
            @RequestBody UserUpdateRequestDTO userUpdateRequestDTO) {
        UserResponseDTO updatedUser = userService.updateUser(userId, userUpdateRequestDTO);
        return ResponseEntity.ok(updatedUser);
    }

    @PatchMapping("/{userId}/profile")
    @PreAuthorize("hasRole('ADMIN') or @securityChecker.isOwner(#userId, authentication.name)")
    public ResponseEntity<UserResponseDTO> updateUserProfile(
            @PathVariable UUID userId,
            @RequestBody UserUpdateRequestDTO userUpdateRequestDTO) {
        UserResponseDTO updatedUser = userService.updateUser(userId, userUpdateRequestDTO);
        return ResponseEntity.ok(updatedUser);
    }

    @GetMapping
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        List<UserResponseDTO> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponseDTO> updateUserStatus(
            @PathVariable UUID userId,
            @RequestParam("status") String status) {
        UserResponseDTO updatedUser = userService.updateUserStatus(userId, status);
        return ResponseEntity.ok(updatedUser);
    }

    @PostMapping("/ban")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> banUser(@RequestParam String email) {
        userService.blockUser(email);
        return ResponseEntity.ok("Khóa tài khoản thành công.");
    }

    @PostMapping("/{userId}/register-session")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> registerSession(@PathVariable UUID userId, @RequestParam String sessionId) {
        userService.registerUserSession(sessionId, userId);
        return ResponseEntity.ok("Đăng ký session thành công.");
    }

    @GetMapping("/{sessionId}/get-user-by-session")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UUID> getUserBySessionId(@PathVariable String sessionId) {
        UUID userId = userService.getUserIdBySession(sessionId);
        return ResponseEntity.ok(userId);
    }

    @DeleteMapping("/{sessionId}/remove-session")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> removeSession(@PathVariable String sessionId) {
        userService.removeUserSession(sessionId);
        return ResponseEntity.ok("Xóa session thành công.");
    }

    @GetMapping("/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponseDTO> getUserById(@PathVariable UUID userId) {
        UserResponseDTO user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }

    @Component("securityChecker")
    public static class SecurityChecker {
        @Autowired
        private UserService userService;

        public boolean isOwner(UUID targetUserId, String currentLoginEmail) {
            UserResponseDTO targetUser = userService.getUserById(targetUserId);
            return targetUser.getEmail().equals(currentLoginEmail);
        }
    }

    @PostMapping("/verify-current-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> verifyCurrentPassword(
            @RequestParam UUID userId,
            @RequestParam String currentPassword) {
        boolean isValid = userService.verifyCurrentPassword(userId, currentPassword);
        if (isValid) {
            // Nếu đúng mk cũ, tự động gửi OTP luôn
            UserResponseDTO user = userService.getUserById(userId);
            if (authService instanceof AuthServiceImpl) {
                ((AuthServiceImpl) authService).sendResetPasswordOtp(user.getEmail());
            }
            return ResponseEntity.ok("Mật khẩu chính xác. Mã OTP đã được gửi.");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Mật khẩu hiện tại không chính xác.");
        }
    }

    @PostMapping("/change-password-otp")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> changePasswordOtp(@RequestParam String email) {
        if (authService instanceof AuthServiceImpl) {
            ((AuthServiceImpl) authService).sendResetPasswordOtp(email);
        }
        return ResponseEntity.ok("Mã OTP đã được gửi.");
    }
}
