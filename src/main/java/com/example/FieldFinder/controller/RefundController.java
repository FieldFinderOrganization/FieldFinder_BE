package com.example.FieldFinder.controller;

import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.dto.res.RefundRequestResponseDTO;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.UserDiscount;
import com.example.FieldFinder.repository.RefundRequestRepository;
import com.example.FieldFinder.repository.UserDiscountRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;
    private final UserDiscountRepository userDiscountRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final UserRepository userRepository;

    @GetMapping("/by-source")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RefundRequestResponseDTO> getBySource(
            @RequestParam("type") String type,
            @RequestParam("id") String sourceId) {
        RefundSourceType sourceType;
        try {
            sourceType = RefundSourceType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Optional<RefundRequest> opt = refundService.findBySource(sourceType, sourceId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        RefundRequest req = opt.get();
        BigDecimal remaining = null;
        if (req.getIssuedDiscount() != null && req.getUser() != null) {
            Optional<UserDiscount> ud = userDiscountRepository.findByUserAndDiscount(
                    req.getUser(), req.getIssuedDiscount());
            if (ud.isPresent()) {
                remaining = ud.get().getRemainingValue();
            }
        }

        return ResponseEntity.ok(RefundRequestResponseDTO.fromEntity(req, remaining));
    }

    /** Lịch sử hoàn tiền của user hiện tại (voucher + tiền mặt). */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> mine(Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        List<RefundRequestResponseDTO> out = refundRequestRepository
                .findByUser_UserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> {
                    BigDecimal remaining = null;
                    if (r.getIssuedDiscount() != null && r.getUser() != null) {
                        remaining = userDiscountRepository
                                .findByUserAndDiscount(r.getUser(), r.getIssuedDiscount())
                                .map(UserDiscount::getRemainingValue).orElse(null);
                    }
                    return RefundRequestResponseDTO.fromEntity(r, remaining);
                })
                .toList();
        return ResponseEntity.ok(out);
    }

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
                return userRepository.findByEmail(email).map(u -> u.getUserId()).orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
    }
}