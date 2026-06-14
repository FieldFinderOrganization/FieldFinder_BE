package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.FavoritePitchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/favorites/pitches")
@PreAuthorize("isAuthenticated()")
public class FavoritePitchController {

    private final FavoritePitchService favoritePitchService;
    private final UserRepository userRepository;

    public FavoritePitchController(FavoritePitchService favoritePitchService,
                                   UserRepository userRepository) {
        this.favoritePitchService = favoritePitchService;
        this.userRepository = userRepository;
    }

    /** Trích userId từ JWT token (giống pattern ở PitchController). */
    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails) {
                email = ((UserDetails) principal).getUsername();
            } else if (principal instanceof String s) {
                email = s;
            }
            if (email != null) {
                return userRepository.findByEmail(email)
                        .map(u -> u.getUserId())
                        .orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Danh sách sân yêu thích đầy đủ — dùng cho màn "Sân yêu thích". */
    @GetMapping
    public ResponseEntity<?> getFavoritePitches(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
        }
        List<PitchResponseDTO> pitches = favoritePitchService.listPitches(userId);
        return ResponseEntity.ok(pitches);
    }

    /** Chỉ trả pitchId đã yêu thích — FE đổ trạng thái nút tim. */
    @GetMapping("/ids")
    public ResponseEntity<?> getFavoritePitchIds(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
        }
        return ResponseEntity.ok(favoritePitchService.listIds(userId));
    }

    @PostMapping("/{pitchId}")
    public ResponseEntity<?> addFavorite(@PathVariable UUID pitchId, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
        }
        favoritePitchService.add(userId, pitchId);
        return ResponseEntity.ok(Map.of("message", "Đã thêm vào sân yêu thích."));
    }

    @DeleteMapping("/{pitchId}")
    public ResponseEntity<?> removeFavorite(@PathVariable UUID pitchId, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Không xác định được người dùng!"));
        }
        favoritePitchService.remove(userId, pitchId);
        return ResponseEntity.noContent().build();
    }
}
