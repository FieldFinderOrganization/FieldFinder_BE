package com.example.FieldFinder.service;

import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.UserRepository;
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

    // @Autowired
    // private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void saveData(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void saveDataWithTTL(String key, String value, long ttl, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, ttl, unit);
    }

    public String getData(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public void deleteData(String key) {
        redisTemplate.delete(key);
    }

    public void deleteByPattern(String pattern) {
        java.util.Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
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

        // Redis chết không được làm hỏng việc resolve userId (vd: GET /notifications sẽ 401 và
        // ẩn hết thông báo dù DB có row) — bọc try/catch để rơi về tra DB.
        try {
            String cachedUserId = redisTemplate.opsForValue().get(cacheKey);
            if (cachedUserId != null) {
                return UUID.fromString(cachedUserId);
            }
        } catch (Exception e) {
            System.err.println("Redis lỗi khi đọc USER_ID_MAPPING, fallback DB: " + e.getMessage());
        }

        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null) {
            String userIdStr = user.getUserId().toString();
            try {
                redisTemplate.opsForValue().set(cacheKey, userIdStr, 7, TimeUnit.DAYS);
            } catch (Exception e) {
                System.err.println("Redis lỗi khi ghi USER_ID_MAPPING: " + e.getMessage());
            }
            return user.getUserId();
        }

        return null;
    }

    public void blacklistJwt(String jti, long ttlSeconds) {
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set("JWT_BLACKLIST:" + jti, "1", ttlSeconds, TimeUnit.SECONDS);
        }
    }

    public boolean isJwtBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("JWT_BLACKLIST:" + jti));
    }

    public void savePasskeyChallenge(String challengeId, String subject, String challengeBase64) {
        String key = "PASSKEY:CHALLENGE:" + challengeId;
        redisTemplate.opsForValue().set(key, subject + "|" + challengeBase64, 5, TimeUnit.MINUTES);
    }

    public String[] consumePasskeyChallenge(String challengeId) {
        String key = "PASSKEY:CHALLENGE:" + challengeId;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "PassKey challenge không hợp lệ hoặc đã hết hạn (5 phút). Vui lòng thử lại.");
        }

        redisTemplate.delete(key);

        int separatorIdx = value.indexOf('|');
        return new String[]{
                value.substring(0, separatorIdx),
                value.substring(separatorIdx + 1)
        };
    }
}
