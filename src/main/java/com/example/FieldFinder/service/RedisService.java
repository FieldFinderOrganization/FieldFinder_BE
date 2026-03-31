package com.example.FieldFinder.service;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RedisService {

    public enum Role {
        USER, ADMIN, PROVIDER
    }

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void saveData(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public String getData(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public boolean isUserBanned(String email) {
        String isBanned = redisTemplate.opsForValue().get("BANNED_USER: " + email);
        return "true".equals(isBanned);
    }

    public String getUserRole(String email) {
        String redisKey = "USER_ROLE:" + email;

        String role = redisTemplate.opsForValue().get(redisKey);

        if (role != null) {
            return role;
        }

        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null) {
            role = user.getRole().name();

            redisTemplate.opsForValue().set(redisKey, role, 1, TimeUnit.HOURS);
            return role;
        }

        return null;
    }

    public void saveUserRole(String email, String role) {
        String redisKey = "USER_ROLE:" + email;

        redisTemplate.opsForValue().set(redisKey, role, 1, TimeUnit.HOURS);
    }

    public UUID getUserIdByEmail(String email) {
        String cacheKey = "USER_ID_MAPPING:" + email;

        String cachedUserId = redisTemplate.opsForValue().get(cacheKey);

        if (cachedUserId != null) {
            return UUID.fromString(cachedUserId);
        }

        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null) {
            String userIdStr = user.getUserId().toString();

            redisTemplate.opsForValue().set(cacheKey, userIdStr, 7, TimeUnit.DAYS);
            return user.getUserId();
        }

        return null;
    }
}
