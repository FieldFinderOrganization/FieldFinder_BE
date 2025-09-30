package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.LoginRequestDTO;
import com.example.FieldFinder.dto.req.UserRequestDTO;
import com.example.FieldFinder.dto.req.UserUpdateRequestDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.UserService;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public UserResponseDTO createUser(UserRequestDTO userRequestDTO) {
        if (userRepository.existsByEmail(userRequestDTO.getEmail())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(403),"Email already exists. Please use a different email.");
        }

        String encodedPassword = passwordEncoder.encode(userRequestDTO.getPassword());
        User user = userRequestDTO.toEntity(null, encodedPassword);

        User savedUser = userRepository.save(user);
        return UserResponseDTO.toDto(savedUser);
    }


    @Override
    public UserResponseDTO loginUser(LoginRequestDTO loginRequestDTO) {
        User user = userRepository.findByEmail(loginRequestDTO.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        // Kiểm tra mật khẩu bằng BCrypt
        if (!passwordEncoder.matches(loginRequestDTO.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        return UserResponseDTO.toDto(user);
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

        // Lưu vào database
        User updatedUser = userRepository.save(user);
        return UserResponseDTO.toDto(updatedUser);
    }
    @Override
    public List<UserResponseDTO> getAllUsers() {
        List<User> users = userRepository.findAll();
        return users.stream().map(UserResponseDTO::toDto).collect(Collectors.toList());
    }


}
