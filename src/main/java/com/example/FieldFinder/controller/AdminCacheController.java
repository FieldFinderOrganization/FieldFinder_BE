package com.example.FieldFinder.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/cache")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCacheController {

    private final RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/evict-products")
    public ResponseEntity<Map<String, Object>> evictProductCaches() {
        Map<String, Object> result = new HashMap<>();
        result.put("product_detail", evictByPattern("product_detail::*"));
        result.put("top_selling", evictByPattern("top_selling::*"));
        result.put("products_category", evictByPattern("products_category::*"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/evict-all")
    public ResponseEntity<Map<String, Object>> evictAll() {
        Map<String, Object> result = new HashMap<>();
        result.put("product_detail", evictByPattern("product_detail::*"));
        result.put("top_selling", evictByPattern("top_selling::*"));
        result.put("products_category", evictByPattern("products_category::*"));
        result.put("cart", evictByPattern("cart:*"));
        return ResponseEntity.ok(result);
    }

    private long evictByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) return 0L;
        Long deleted = redisTemplate.delete(keys);
        return deleted != null ? deleted : 0L;
    }
}
