package com.example.FieldFinder.controller;

import com.example.FieldFinder.Enum.ReviewStatus;
import com.example.FieldFinder.dto.req.ModerationActionRequestDTO;
import com.example.FieldFinder.dto.res.ItemReviewResponseDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;
import com.example.FieldFinder.service.ReviewModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kiểm duyệt thủ công đánh giá (chỉ ADMIN).
 * Hàng đợi mặc định lọc theo trạng thái PENDING (đã qua auto, chờ duyệt tay).
 */
@RestController
@RequestMapping("/api/admin/review-moderation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ReviewModerationController {

    private final ReviewModerationService moderationService;

    @GetMapping("/pitch-reviews")
    public ResponseEntity<List<ReviewResponseDTO>> getPitchReviews(
            @RequestParam(defaultValue = "PENDING") ReviewStatus status) {
        return ResponseEntity.ok(moderationService.getPitchReviews(status));
    }

    @GetMapping("/product-reviews")
    public ResponseEntity<List<ItemReviewResponseDTO>> getProductReviews(
            @RequestParam(defaultValue = "PENDING") ReviewStatus status) {
        return ResponseEntity.ok(moderationService.getProductReviews(status));
    }

    @PutMapping("/pitch-reviews/{reviewId}/approve")
    public ResponseEntity<ReviewResponseDTO> approvePitchReview(@PathVariable UUID reviewId) {
        return ResponseEntity.ok(moderationService.approvePitchReview(reviewId));
    }

    @PutMapping("/pitch-reviews/{reviewId}/reject")
    public ResponseEntity<ReviewResponseDTO> rejectPitchReview(
            @PathVariable UUID reviewId,
            @RequestBody(required = false) ModerationActionRequestDTO body) {
        String reason = body != null ? body.getReason() : null;
        return ResponseEntity.ok(moderationService.rejectPitchReview(reviewId, reason));
    }

    @PutMapping("/product-reviews/{reviewId}/approve")
    public ResponseEntity<ItemReviewResponseDTO> approveProductReview(@PathVariable Long reviewId) {
        return ResponseEntity.ok(moderationService.approveProductReview(reviewId));
    }

    @PutMapping("/product-reviews/{reviewId}/reject")
    public ResponseEntity<ItemReviewResponseDTO> rejectProductReview(
            @PathVariable Long reviewId,
            @RequestBody(required = false) ModerationActionRequestDTO body) {
        String reason = body != null ? body.getReason() : null;
        return ResponseEntity.ok(moderationService.rejectProductReview(reviewId, reason));
    }

    @GetMapping("/counts")
    public ResponseEntity<Map<String, Object>> getCounts() {
        return ResponseEntity.ok(moderationService.getCounts());
    }
}
