package com.example.FieldFinder.ai.cache;

import com.example.FieldFinder.ai.gemini.GeminiClient;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.ProductService;
import com.example.FieldFinder.service.RedisService;
import com.example.FieldFinder.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Cache Redis dùng chung cho trợ lý AI: catalog sản phẩm/sân + resolve userId.
 * Tách khỏi AIChat để các intent handler (image-search, product, pitch) cùng dùng,
 * tránh phụ thuộc vòng vào AIChat. TTL/hành vi giữ nguyên 1:1.
 */
@Component
public class AiCatalogCache {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisService redisService;
    private final ProductService productService;
    private final PitchService pitchService;
    private final UserService userService;

    public AiCatalogCache(RedisService redisService, ProductService productService,
                          PitchService pitchService, UserService userService) {
        this.redisService = redisService;
        this.productService = productService;
        this.pitchService = pitchService;
        this.userService = userService;
    }

    /** Ưu tiên user đã đăng nhập (SecurityContext → email → uid), fallback theo sessionId. */
    public UUID resolveCurrentUserId(String sessionId) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String) {
                String email = (String) auth.getPrincipal();
                if (email != null && !email.equals("anonymousUser") && !email.isBlank()) {
                    UUID uid = redisService.getUserIdByEmail(email);
                    if (uid != null) return uid;
                }
            }
        } catch (Exception e) {
            System.err.println("resolveCurrentUserId error: " + e.getMessage());
        }
        if (sessionId != null) {
            return userService.getUserIdBySession(sessionId);
        }
        return null;
    }

    /** P2: Cache `getAllPitches(0,50)` TTL 90s. */
    public List<PitchResponseDTO> getAllPitchesCached() {
        String key = "ai:pitches:all:50";
        try {
            String cached = redisService.getData(key);
            if (cached != null) {
                return mapper.readValue(cached, new TypeReference<List<PitchResponseDTO>>() {});
            }
        } catch (Exception ignored) {}
        List<PitchResponseDTO> data = pitchService.getAllPitches(PageRequest.of(0, 50), null, null, null).getContent();
        try {
            redisService.saveDataWithTTL(key, mapper.writeValueAsString(data), 90, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return data;
    }

    /** P3+: Cache productsByIds map. Key = sorted ids hash. TTL 60s. */
    public Map<Long, ProductResponseDTO> getProductsByIdsCached(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        List<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        String key = "ai:products:byids:" + GeminiClient.sha256Hex(sorted.toString());
        try {
            String cached = redisService.getData(key);
            if (cached != null) {
                List<ProductResponseDTO> list = mapper.readValue(cached,
                        new TypeReference<List<ProductResponseDTO>>() {});
                Map<Long, ProductResponseDTO> map = new LinkedHashMap<>();
                for (ProductResponseDTO p : list) {
                    if (p != null && p.getId() != null) map.put(p.getId(), p);
                }
                return map;
            }
        } catch (Exception ignored) {}
        Map<Long, ProductResponseDTO> fresh = productService.getProductsByIds(ids, null);
        try {
            redisService.saveDataWithTTL(key, mapper.writeValueAsString(new ArrayList<>(fresh.values())),
                    60, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return fresh;
    }

    /** P2: Cache `getProductsForAiAssistant(userId)` TTL 60s. */
    public List<ProductResponseDTO> getProductsForAiAssistantCached(UUID userId) {
        String key = "ai:products:assistant:" + (userId == null ? "anon" : userId.toString());
        try {
            String cached = redisService.getData(key);
            if (cached != null) {
                return mapper.readValue(cached, new TypeReference<List<ProductResponseDTO>>() {});
            }
        } catch (Exception ignored) {}
        List<ProductResponseDTO> data = productService.getProductsForAiAssistant(userId);
        try {
            redisService.saveDataWithTTL(key, mapper.writeValueAsString(data), 60, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return data;
    }
}
