package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.LoginRequestDTO;
import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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

    // Login user
    @PostMapping("/login")
    public ResponseEntity<UserResponseDTO> loginUser(@RequestBody LoginRequestDTO loginRequestDTO) {
        UserResponseDTO loggedInUser = userService.loginUser(loginRequestDTO);
        return ResponseEntity.ok(loggedInUser);
    }
    @PutMapping("/users/{userId}")
    public ResponseEntity<UserResponseDTO> updateUser(
            @PathVariable UUID userId,
            @RequestBody UserUpdateRequestDTO userUpdateRequestDTO) {

        UserResponseDTO updatedUser = userService.updateUser(userId, userUpdateRequestDTO);
        return ResponseEntity.ok(updatedUser);
    }
    @GetMapping("/users")
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        List<UserResponseDTO> users = userService.getAllUsers();
        return ResponseEntity.ok(users);
    }


}
