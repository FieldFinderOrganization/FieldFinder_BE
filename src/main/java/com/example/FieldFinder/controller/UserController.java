package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.AuthRequestDTO;
import com.example.FieldFinder.dto.req.LoginRequestDTO;
import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.service.UserService;
import com.google.api.client.util.Value;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Register a new user
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

            return ResponseEntity.ok(Map.of(
                    "message", "Login successful",
                    "user", loggedInUser
            ));

        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid Firebase ID token", "details", e.getMessage()));
        }
    }

    @PostMapping("/login-social")
    public ResponseEntity<?> loginWithSocial(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");

        try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);

            UserResponseDTO loggedInUser = userService.loginWithFirebase(decodedToken);

            return ResponseEntity.ok(Map.of(
                    "message", "Login successful",
                    "user", loggedInUser
            ));

        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid Firebase ID token", "details", e.getMessage()));
        }
    }


    // Update user
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponseDTO> updateUser(
            @PathVariable UUID userId,
            @RequestBody UserUpdateRequestDTO userUpdateRequestDTO) {

        UserResponseDTO updatedUser = userService.updateUser(userId, userUpdateRequestDTO);
        return ResponseEntity.ok(updatedUser);
    }

    // Get all users
    @GetMapping
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        List<UserResponseDTO> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    // Update user status
    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserResponseDTO> updateUserStatus(
            @PathVariable UUID userId,
            @RequestParam("status") String status) {

        UserResponseDTO updatedUser = userService.updateUserStatus(userId, status);
        return ResponseEntity.ok(updatedUser);
    }

    // Forgot password (send email)
    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestParam String email) {
        userService.sendPasswordResetEmail(email);
        return ResponseEntity.ok("Password reset email sent.");
    }

    // Reset password
    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(
            @RequestParam String token,
            @RequestParam String newPassword) {

        userService.resetPassword(token, newPassword);
        return ResponseEntity.ok("Password updated successfully.");
    }

    @PostMapping("/{userId}/register-session")
    public ResponseEntity<String> registerSession(@PathVariable UUID userId, @RequestParam String sessionId) {
        userService.registerUserSession(sessionId, userId);
        return ResponseEntity.ok("Session registered successfully.");
    }

    @GetMapping("/{sessionId}/get-user-by-session")
    public ResponseEntity<UUID> getUserBySessionId(@PathVariable String sessionId) {
        UUID userId = userService.getUserIdBySession(sessionId);
        return ResponseEntity.ok(userId);
    }

    @DeleteMapping("/{sessionId}/remove-session")
    public ResponseEntity<String> removeSession(@PathVariable String sessionId) {
        userService.removeUserSession(sessionId);
        return ResponseEntity.ok("Session removed successfully.");
    }

    // Get user by ID
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponseDTO> getUserById(
            @PathVariable UUID userId) {

        UserResponseDTO user = userService.getUserById(userId);
        return ResponseEntity.ok(user);
    }


}
