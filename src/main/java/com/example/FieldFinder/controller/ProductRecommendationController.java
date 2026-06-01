package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.ProductResponseDTO;
import com.example.FieldFinder.dto.res.SuggestedProductsResponseDTO;
import com.example.FieldFinder.service.ProductRecommendationService;
import com.example.FieldFinder.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductRecommendationController {

    private final ProductRecommendationService productRecommendationService;
    private final RedisService redisService;

    private UUID resolveUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        try {
            Object principal = auth.getPrincipal();
            String email = principal instanceof UserDetails ud ? ud.getUsername() : principal.toString();
            return redisService.getUserIdByEmail(email);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/{productId}/suggested")
    public ResponseEntity<SuggestedProductsResponseDTO> getSuggested(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(productRecommendationService.getSuggested(productId, userId, limit));
    }

    @GetMapping("/suggested-for-pitch")
    public ResponseEntity<List<ProductResponseDTO>> getSuggestedForPitch(
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(productRecommendationService.getSuggestedFootballProducts(userId, limit));
    }
}
