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

    /**
     * Thêm JWT (jti) vào blacklist với TTL = thời gian còn lại của token.
     */
    public void blacklistJwt(String jti, long ttlSeconds) {
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set("JWT_BLACKLIST:" + jti, "1", ttlSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Kiểm tra JWT có trong blacklist không.
     */
    public boolean isJwtBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("JWT_BLACKLIST:" + jti));
    }

    // -------------------------------------------------------
    // PassKey / WebAuthn Challenge
    // -------------------------------------------------------

    /**
     * Lưu PassKey challenge vào Redis.
     * Format: "PASSKEY:CHALLENGE:{challengeId}" → "{subject}|{challengeBase64url}"
     *
     * @param challengeId    UUID làm Redis key suffix
     * @param subject        userId (khi register) hoặc email (khi login)
     * @param challengeBase64 challenge bytes đã encode base64url
     */
    public void savePasskeyChallenge(String challengeId, String subject, String challengeBase64) {
        String key = "PASSKEY:CHALLENGE:" + challengeId;
        // Dùng "|" làm separator — ký tự này không có trong base64url hay email
        redisTemplate.opsForValue().set(key, subject + "|" + challengeBase64, 5, TimeUnit.MINUTES);
    }

    /**
     * Lấy và XÓA ngay challenge khỏi Redis (one-time use — chống replay attack).
     *
     * @return String[0] = subject, String[1] = challengeBase64url
     * @throws org.springframework.web.server.ResponseStatusException 401 nếu challenge không tồn tại / hết hạn
     */
    public String[] consumePasskeyChallenge(String challengeId) {
        String key = "PASSKEY:CHALLENGE:" + challengeId;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "PassKey challenge không hợp lệ hoặc đã hết hạn (5 phút). Vui lòng thử lại.");
        }

        // Xóa ngay lập tức — dùng 1 lần duy nhất
        redisTemplate.delete(key);

        int separatorIdx = value.indexOf('|');
        return new String[]{
                value.substring(0, separatorIdx),   // subject
                value.substring(separatorIdx + 1)   // challengeBase64
        };
    }
}
