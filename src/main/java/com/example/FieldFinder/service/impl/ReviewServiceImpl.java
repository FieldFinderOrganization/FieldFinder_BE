package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.Enum.ModerationSource;
import com.example.FieldFinder.Enum.ReviewStatus;
import com.example.FieldFinder.dto.req.ReviewRequestDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Review;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.moderation.ModerationResult;
import com.example.FieldFinder.moderation.ModerationService;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.ReviewService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReviewServiceImpl implements ReviewService {
    private final ReviewRepository reviewRepository;
    private final PitchRepository pitchRepository;
    private final UserRepository userRepository;
    private final ModerationService moderationService;

    public ReviewServiceImpl(ReviewRepository reviewRepository, PitchRepository pitchRepository,
                             UserRepository userRepository, ModerationService moderationService) {
        this.reviewRepository = reviewRepository;
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
        this.moderationService = moderationService;
    }

    @Override
    public ReviewResponseDTO addReview(ReviewRequestDTO requestDTO) {
        Pitch pitch = pitchRepository.findById(requestDTO.getPitchId()).orElseThrow(() -> new RuntimeException("Pitch not found!"));
        User user = userRepository.findById(requestDTO.getUserId()).orElseThrow(() -> new RuntimeException("User not found!"));

        if (pitch.getProviderAddress() != null
                && pitch.getProviderAddress().getProvider() != null
                && pitch.getProviderAddress().getProvider().getUser() != null) {
            java.util.UUID providerUserId = pitch.getProviderAddress().getProvider().getUser().getUserId();
            if (providerUserId.equals(user.getUserId())) {
                throw new RuntimeException("Chủ sân không thể tự bình luận hoặc đánh giá sân của chính mình!");
            }
        }

        // Đã có đánh giá: chặn nếu bản cũ chưa bị từ chối; nếu đã bị từ chối thì cho gửi lại (xoá bản cũ).
        reviewRepository.findByPitch_PitchIdAndUser_UserId(requestDTO.getPitchId(), requestDTO.getUserId())
                .ifPresent(existing -> {

                    if (existing.getStatus() == ReviewStatus.REJECTED) {
                        reviewRepository.delete(existing);
                    } else {
                        throw new RuntimeException("User has already reviewed this pitch!");
                    }
                });

        Review review = Review.builder()
                .pitch(pitch)
                .user(user)
                .rating(requestDTO.getRating())
                .comment(requestDTO.getComment())
                .createdAt(LocalDateTime.now())
                .build();

        applyAutoModeration(review, requestDTO.getComment());

        review = reviewRepository.save(review);
        return mapToDTO(review);
    }

    /**
     * Bước 1 — kiểm duyệt tự động. Auto chặn -> REJECTED (AUTO); auto cho qua -> PENDING chờ duyệt thủ công.
     */
    private void applyAutoModeration(Review review, String comment) {
        ModerationResult result = moderationService.moderate(comment);
        if (result.rejected()) {
            review.setStatus(ReviewStatus.REJECTED);
            review.setModerationSource(ModerationSource.AUTO);
            review.setModerationReason(result.reason());
            review.setModeratedAt(LocalDateTime.now());
        } else {
            review.setStatus(ReviewStatus.PENDING);
        }
    }

    @Override
    public ReviewResponseDTO updateReview(UUID reviewId, ReviewRequestDTO requestDTO) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new RuntimeException("Review not found!"));
        review.setRating(requestDTO.getRating());
        review.setComment(requestDTO.getComment());
        // Sửa nội dung -> phải kiểm duyệt lại từ đầu.
        review.setModerationReason(null);
        review.setModerationSource(null);
        review.setModeratedAt(null);
        applyAutoModeration(review, requestDTO.getComment());
        review = reviewRepository.save(review);
        return mapToDTO(review);
    }

    @Override
    public void deleteReview(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new RuntimeException("Review not found!"));
        reviewRepository.delete(review);
    }

    @Override
    public List<ReviewResponseDTO> getReviewsByPitch(UUID pitchId) {
        // Công khai: chỉ trả về đánh giá đã được duyệt.
        List<Review> reviews = reviewRepository.findByPitch_PitchIdAndStatus(pitchId, ReviewStatus.APPROVED);
        return reviews.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    public List<ReviewResponseDTO> getReviewsByUser(UUID userId) {
        return reviewRepository.findByUser_UserId(userId).stream()
                .map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    public Double getAverageRating(UUID pitchId) {
        return reviewRepository.findAverageRatingByPitchId(pitchId);
    }

    private ReviewResponseDTO mapToDTO(Review review) {
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
}