package com.example.FieldFinder.service.impl;

package com.pitchbooking.application.service.impl;

import com.pitchbooking.application.dto.UserDTO;
import com.pitchbooking.application.mapper.UserMapper;
import com.pitchbooking.application.repository.UserRepository;
import com.pitchbooking.application.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public UserDTO getUserById(UUID userId) {
        return userRepository.findById(userId)
                .map(UserMapper.INSTANCE::toDTO)
                .orElse(null);
    }
}
