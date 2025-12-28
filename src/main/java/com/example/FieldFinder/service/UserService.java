package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.LoginRequestDTO;
import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.google.firebase.auth.FirebaseToken;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponseDTO createUser(UserRequestDTO userRequestDTO);
    UserResponseDTO loginUser(FirebaseToken decodedToken);
    UserResponseDTO updateUser(UUID userId, UserUpdateRequestDTO userUpdateRequestDTO);
    List<UserResponseDTO> getAllUsers();
    UserResponseDTO updateUserStatus(UUID userId, String status);
    void sendPasswordResetEmail(String email);
    void resetPassword(String token, String newPassword);

    UserResponseDTO loginWithFirebase(FirebaseToken decodedToken);

    UUID getUserIdBySession(String sessionId);

    void registerUserSession(String sessionId, UUID userId);

    void removeUserSession(String sessionId);
}
