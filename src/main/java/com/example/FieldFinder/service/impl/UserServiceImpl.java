package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.UserDto;
import com.example.FieldFinder.mapper.UserMapper;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public UserDto getUserById(UUID userId) {
        return userRepository.findById(userId)
                .map(UserMapper.INSTANCE::toDTO)
                .orElse(null);
    }
}
