package com.example.FieldFinder.ai;

import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class AiChatSessionContextStore {

    private static final long SESSION_TTL_HOURS = 2;
    private static final String KEY_PREFIX = "ai:chat:session:";
    private static final String LAST_PRODUCT = "lastProduct";
    private static final String LAST_PITCH = "lastPitch";
    private static final String LAST_SIZE = "lastSize";
    private static final String LAST_ACTIVITY = "lastActivity";
    private static final String LAST_CATEGORY_KEYWORD = "lastCategoryKeyword";
    private static final String LAST_PRODUCT_TYPE = "lastProductType";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProductResponseDTO getLastProduct(String sessionId) {
        return readJson(sessionId, LAST_PRODUCT, ProductResponseDTO.class);
    }

    public void setLastProduct(String sessionId, ProductResponseDTO product) {
        writeJson(sessionId, LAST_PRODUCT, product);
    }

    public PitchResponseDTO getLastPitch(String sessionId) {
        return readJson(sessionId, LAST_PITCH, PitchResponseDTO.class);
    }

    public void setLastPitch(String sessionId, PitchResponseDTO pitch) {
        writeJson(sessionId, LAST_PITCH, pitch);
    }

    public String getLastSize(String sessionId) {
        return readString(sessionId, LAST_SIZE);
    }

    public void setLastSize(String sessionId, String size) {
        writeString(sessionId, LAST_SIZE, size);
    }

    public String getLastActivity(String sessionId) {
        return readString(sessionId, LAST_ACTIVITY);
    }

    public void setLastActivity(String sessionId, String activity) {
        writeString(sessionId, LAST_ACTIVITY, activity);
    }

    public String getLastCategoryKeyword(String sessionId) {
        return readString(sessionId, LAST_CATEGORY_KEYWORD);
    }

    public void setLastCategoryKeyword(String sessionId, String categoryKeyword) {
        writeString(sessionId, LAST_CATEGORY_KEYWORD, categoryKeyword);
    }

    public String getLastProductType(String sessionId) {
        return readString(sessionId, LAST_PRODUCT_TYPE);
    }

    public void setLastProductType(String sessionId, String productType) {
        writeString(sessionId, LAST_PRODUCT_TYPE, productType);
    }

    private <T> T readJson(String sessionId, String field, Class<T> type) {
        if (blank(sessionId)) return null;
        String key = key(sessionId);
        String raw = (String) redisTemplate.opsForHash().get(key, field);
        if (raw == null || raw.isBlank()) return null;
        touch(key);
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            redisTemplate.opsForHash().delete(key, field);
            return null;
        }
    }

    private void writeJson(String sessionId, String field, Object value) {
        if (blank(sessionId) || value == null) return;
        try {
            writeString(sessionId, field, objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
        }
    }

    private String readString(String sessionId, String field) {
        if (blank(sessionId)) return null;
        String key = key(sessionId);
        String value = (String) redisTemplate.opsForHash().get(key, field);
        if (value != null) touch(key);
        return value;
    }

    private void writeString(String sessionId, String field, String value) {
        if (blank(sessionId) || value == null || value.isBlank()) return;
        String key = key(sessionId);
        redisTemplate.opsForHash().put(key, field, value);
        touch(key);
    }

    private void touch(String key) {
        redisTemplate.expire(key, SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
