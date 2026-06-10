package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.ModerationSource;
import com.example.FieldFinder.Enum.ReviewStatus;
import com.example.FieldFinder.dto.res.ItemReviewResponseDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;
import com.example.FieldFinder.entity.Item_Review;
import com.example.FieldFinder.entity.Review;
import com.example.FieldFinder.repository.ItemReviewRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.service.ReviewModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewModerationServiceImpl implements ReviewModerationService {

    private final ReviewRepository reviewRepository;
    private final ItemReviewRepository itemReviewRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ReviewResponseDTO> getPitchReviews(ReviewStatus status) {
        return reviewRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::mapPitch)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemReviewResponseDTO> getProductReviews(ReviewStatus status) {
        return itemReviewRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::mapProduct)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ReviewResponseDTO approvePitchReview(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));
        review.setStatus(ReviewStatus.APPROVED);
        review.setModerationSource(ModerationSource.MANUAL);
        review.setModerationReason(null);
        review.setModeratedAt(LocalDateTime.now());
        return mapPitch(reviewRepository.save(review));
    }

    @Override
    @Transactional
    public ReviewResponseDTO rejectPitchReview(UUID reviewId, String reason) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));
        review.setStatus(ReviewStatus.REJECTED);
        review.setModerationSource(ModerationSource.MANUAL);
        review.setModerationReason(reason);
        review.setModeratedAt(LocalDateTime.now());
        return mapPitch(reviewRepository.save(review));
    }

    @Override
    @Transactional
    public ItemReviewResponseDTO approveProductReview(Long reviewId) {
        Item_Review review = itemReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));
        review.setStatus(ReviewStatus.APPROVED);
        review.setModerationSource(ModerationSource.MANUAL);
        review.setModerationReason(null);
        review.setModeratedAt(LocalDateTime.now());
        return mapProduct(itemReviewRepository.save(review));
    }

    @Override
    @Transactional
    public ItemReviewResponseDTO rejectProductReview(Long reviewId, String reason) {
        Item_Review review = itemReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đánh giá"));
        review.setStatus(ReviewStatus.REJECTED);
        review.setModerationSource(ModerationSource.MANUAL);
        review.setModerationReason(reason);
        review.setModeratedAt(LocalDateTime.now());
        return mapProduct(itemReviewRepository.save(review));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getCounts() {
        Map<String, Object> pitch = new LinkedHashMap<>();
        pitch.put("pending", reviewRepository.countByStatus(ReviewStatus.PENDING));
        pitch.put("approved", reviewRepository.countByStatus(ReviewStatus.APPROVED));
        pitch.put("rejected", reviewRepository.countByStatus(ReviewStatus.REJECTED));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("pending", itemReviewRepository.countByStatus(ReviewStatus.PENDING));
        product.put("approved", itemReviewRepository.countByStatus(ReviewStatus.APPROVED));
        product.put("rejected", itemReviewRepository.countByStatus(ReviewStatus.REJECTED));

        long pendingTotal = (long) (Long) pitch.get("pending") + (Long) product.get("pending");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pitch", pitch);
        result.put("product", product);
        result.put("pendingTotal", pendingTotal);
        return result;
    }

    private ReviewResponseDTO mapPitch(Review review) {
        return ReviewResponseDTO.builder()
                .reviewId(review.getReviewId())
                .pitchId(review.getPitch().getPitchId())
                .pitchName(review.getPitch().getName())
                .userId(review.getUser().getUserId())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt().toString())
                .userName(review.getUser().getName())
                .userImageUrl(review.getUser().getImageUrl())
                .status(review.getStatus() != null ? review.getStatus().name() : null)
                .moderationReason(review.getModerationReason())
                .build();
    }

    private ItemReviewResponseDTO mapProduct(Item_Review review) {
        return ItemReviewResponseDTO.builder()
                .reviewId(review.getReviewId())
                .userId(review.getUser().getUserId())
                .userName(review.getUser().getName())
                .productId(review.getProduct().getProductId())
                .productName(review.getProduct().getName())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .status(review.getStatus() != null ? review.getStatus().name() : null)
                .moderationReason(review.getModerationReason())
                .build();
    }
}
