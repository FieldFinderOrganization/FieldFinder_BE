package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.LoginRequestDTO;
import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.entity.PasswordResetToken;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.PasswordResetTokenRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.UserService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, EmailService emailService, PasswordResetTokenRepository passwordResetTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
    }

    @Override
    @Transactional
    public UserResponseDTO createUser(UserRequestDTO userRequestDTO) {
        if (userRepository.existsByEmail(userRequestDTO.getEmail())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Email already exists. Please use a different email."
            );
        }

        try {
            // 1. Tạo user trên Firebase Authentication
            UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                    .setEmail(userRequestDTO.getEmail())
                    .setPassword(userRequestDTO.getPassword())
                    .setEmailVerified(false)
                    .setDisabled(false);

            UserRecord firebaseUser = FirebaseAuth.getInstance().createUser(request);

            // 2. Lưu user vào DB (Firebase quản lý password, nên để null trong DB)
            String encodedPassword = passwordEncoder.encode(userRequestDTO.getPassword());

            User user = userRequestDTO.toEntity(firebaseUser.getUid(), encodedPassword);
            User savedUser = userRepository.save(user);

            // Gán UID từ Firebase

            // 3. Trả về DTO
            return UserResponseDTO.toDto(savedUser);

        } catch (FirebaseAuthException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create user in Firebase: " + e.getMessage()
            );
        }
    }

    @Override
    public UserResponseDTO loginUser(LoginRequestDTO loginRequestDTO) {
        try {
            // 1. Verify token từ Firebase
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(loginRequestDTO.getIdToken());
            String uid = decodedToken.getUid();
            String email = decodedToken.getEmail();

            // 2. Tìm user trong DB theo email (hoặc UID)
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

            if (user.getStatus() == User.Status.BLOCKED) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account has been blocked. Please contact admin.");
            }

            // 3. Trả về DTO
            return UserResponseDTO.toDto(user);

        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Firebase token: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID userId, UserUpdateRequestDTO userUpdateRequestDTO) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Kiểm tra xem email có bị trùng không (nếu email thay đổi)
        if (!user.getEmail().equals(userUpdateRequestDTO.getEmail()) && userRepository.existsByEmail(userUpdateRequestDTO.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists. Please use a different email.");
        }

        // Cập nhật thông tin
        user.setName(userUpdateRequestDTO.getName());
        user.setEmail(userUpdateRequestDTO.getEmail());
        user.setPhone(userUpdateRequestDTO.getPhone());
        user.setStatus(userUpdateRequestDTO.getStatus());

        // Lưu vào database
        User updatedUser = userRepository.save(user);
        return UserResponseDTO.toDto(updatedUser);
    }
    @Override
    public List<UserResponseDTO> getAllUsers() {
        List<User> users = userRepository.findAll();
        return users.stream().map(UserResponseDTO::toDto).collect(Collectors.toList());
    }
    @Override
    public UserResponseDTO updateUserStatus(UUID userId, String statusStr) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        try {
            User.Status newStatus = User.Status.valueOf(statusStr.toUpperCase());
            user.setStatus(newStatus);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status. Allowed: ACTIVE, BLOCKED");
        }

        userRepository.save(user);
        return UserResponseDTO.toDto(user);
    }
    @Override
    @Transactional
    public void sendPasswordResetEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Email not found"));

        // Check for an existing token
        PasswordResetToken existingToken = passwordResetTokenRepository.findByUser(user)
                .orElse(null);

        String token;
        if (existingToken != null && existingToken.getExpiryDate().isAfter(LocalDateTime.now())) {
            // Reuse the existing valid token
            token = existingToken.getToken();
        } else {
            // Delete the old token if it exists
            if (existingToken != null) {
                passwordResetTokenRepository.delete(existingToken);
            }
            // Generate and save a new token
            token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .token(token)
                    .user(user)
                    .expiryDate(LocalDateTime.now().plusMinutes(30))
                    .build();
            passwordResetTokenRepository.save(resetToken);
        }

        String resetLink = "http://localhost:8080/api/users/reset-password?token=" + token;

        emailService.send(
                email,
                "Password Reset",
                "Click the link below to reset your password:\n" + resetLink
        );
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid token"));

        if (resetToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        passwordResetTokenRepository.delete(resetToken); // invalidate token
    }

    @Override
    public UserResponseDTO loginWithFirebase(FirebaseToken decodedToken) {
        String uid = decodedToken.getUid();
        String email = decodedToken.getEmail();
        String name = decodedToken.getName();

        // Tìm theo firebaseUid thay vì email để tránh trùng
        User user = userRepository.findByFirebaseUid(uid)
                .orElseGet(() -> {
                    // Nếu chưa có thì tạo mới
                    User newUser = User.builder()
                            .firebaseUid(uid)
                            .email(email)
                            .name(decodedToken.getName() != null ? decodedToken.getName() : "")
                            .phone(null)
                            .password("firebase-user")
                            .role(User.Role.USER)
                            .status(User.Status.ACTIVE)
                            .build();


                    return userRepository.save(newUser);
                });

        return UserResponseDTO.toDto(user);
    }


}

