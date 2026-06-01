package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.SuggestedPitchesResponseDTO;
import com.example.FieldFinder.service.PitchRecommendationService;
import com.example.FieldFinder.service.RedisService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/pitches")
public class PitchRecommendationController {

    private final PitchRecommendationService pitchRecommendationService;
    private final RedisService redisService;

    public PitchRecommendationController(PitchRecommendationService pitchRecommendationService, RedisService redisService) {
        this.pitchRecommendationService = pitchRecommendationService;
        this.redisService = redisService;
    }

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

    @GetMapping("/{pitchId}/suggested")
    public ResponseEntity<SuggestedPitchesResponseDTO> getSuggested(
            @PathVariable UUID pitchId,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        return ResponseEntity.ok(pitchRecommendationService.getSuggested(pitchId, userId, lat, lng, limit));
    }

    @GetMapping("/suggested-for-product")
    public ResponseEntity<SuggestedPitchesResponseDTO> getSuggestedForProduct(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "10") int limit,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        UUID dummyPitchId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        return ResponseEntity.ok(pitchRecommendationService.getSuggested(dummyPitchId, userId, lat, lng, limit));
    }
}
