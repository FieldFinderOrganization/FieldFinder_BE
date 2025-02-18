package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.UserDto;

import java.util.UUID;

public interface UserService {
    UserDto getUserById(UUID userId);
}
