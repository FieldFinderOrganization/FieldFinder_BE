package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.UserDto;
import com.example.FieldFinder.mapper.UserMapper;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    @Override
    public UserDto getUserById(UUID userId) {
        return userRepository.findById(userId)
                .map(UserMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<UserDto> getAllUsers() {
        return List.of();
    }

    @Override
    public UserDto createUser(UserDto user) {
        return null;
    }

    @Override
    public UserDto updateUser(UUID id, UserDto user) {
        return null;
    }

    @Override
    public void deleteUser(UUID id) {

    }
}
