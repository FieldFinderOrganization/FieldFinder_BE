package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.req.ReviewRequestDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Review;
import com.example.FieldFinder.entity.User;
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

    public ReviewServiceImpl(ReviewRepository reviewRepository, PitchRepository pitchRepository, UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
    }

    @Override
    public ReviewResponseDTO addReview(ReviewRequestDTO requestDTO) {
        Pitch pitch = pitchRepository.findById(requestDTO.getPitchId()).orElseThrow(() -> new RuntimeException("Pitch not found!"));
        User user = userRepository.findById(requestDTO.getUserId()).orElseThrow(() -> new RuntimeException("User not found!"));

        Review review = Review.builder()
                .pitch(pitch)
                .user(user)
                .rating(requestDTO.getRating())
                .comment(requestDTO.getComment())
                .createdAt(LocalDateTime.now())
                .build();

        review = reviewRepository.save(review);
        return mapToDTO(review);
    }

    @Override
    public ReviewResponseDTO updateReview(UUID reviewId, ReviewRequestDTO requestDTO) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new RuntimeException("Review not found!"));
        review.setRating(requestDTO.getRating());
        review.setComment(requestDTO.getComment());
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
        List<Review> reviews = reviewRepository.findByPitch_PitchId(pitchId);
        return reviews.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Override
    public Double getAverageRating(UUID pitchId) {
        return reviewRepository.findAverageRatingByPitchId(pitchId);
    }

    private ReviewResponseDTO mapToDTO(Review review) {
        return ReviewResponseDTO.builder()
                .reviewId(review.getReviewId())
                .pitchId(review.getPitch().getPitchId())
                .userId(review.getUser().getUserId())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt().toString())
                .build();
    }
}