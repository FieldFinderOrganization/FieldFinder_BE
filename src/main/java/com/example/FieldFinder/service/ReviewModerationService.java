package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.ReviewStatus;
import com.example.FieldFinder.dto.res.ItemReviewResponseDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bước 2 — kiểm duyệt thủ công. Admin xem hàng đợi theo trạng thái và duyệt/từ chối.
 */
public interface ReviewModerationService {

    List<ReviewResponseDTO> getPitchReviews(ReviewStatus status);

    List<ItemReviewResponseDTO> getProductReviews(ReviewStatus status);

    ReviewResponseDTO approvePitchReview(UUID reviewId);

    ReviewResponseDTO rejectPitchReview(UUID reviewId, String reason);

    ItemReviewResponseDTO approveProductReview(Long reviewId);

    ItemReviewResponseDTO rejectProductReview(Long reviewId, String reason);

    /** Số lượng theo trạng thái cho badge (pitch & product). */
    Map<String, Object> getCounts();
}
