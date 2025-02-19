package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.UserDto;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserDto getUserById(UUID userId);

    List<UserDto> getAllUsers();

    UserDto createUser(UserDto user);

    UserDto updateUser(UUID id, UserDto user);

    void deleteUser(UUID id);
}
