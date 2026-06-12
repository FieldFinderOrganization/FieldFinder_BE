package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.res.NotificationDTO;
import com.example.FieldFinder.repository.NotificationRepository;
import com.example.FieldFinder.service.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final RedisService redisService;

    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal().equals("anonymousUser")) {
            return null;
        }
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails) {
                email = ((UserDetails) principal).getUsername();
            } else if (principal instanceof String) {
                email = (String) principal;
            }
            if (email != null) {
                return redisService.getUserIdByEmail(email);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<NotificationDTO>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return ResponseEntity.status(401).build();
        Page<NotificationDTO> result = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(NotificationDTO::from);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Long> getUnreadCount(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(notificationRepository.countByUserIdAndIsReadFalse(userId));
    }

    @PostMapping("/{id}/mark-read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Map<String, String>> markRead(@PathVariable UUID id,
                                                        Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return ResponseEntity.status(401).build();
        notificationRepository.markRead(id, userId);
        return ResponseEntity.ok(Map.of("message", "OK"));
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Map<String, String>> markAllRead(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) return ResponseEntity.status(401).build();
        notificationRepository.markAllRead(userId);
        return ResponseEntity.ok(Map.of("message", "OK"));
    }
}
