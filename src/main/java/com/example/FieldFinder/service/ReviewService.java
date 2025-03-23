package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ReviewRequestDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ReviewService {
    ReviewResponseDTO addReview(ReviewRequestDTO requestDTO);
    ReviewResponseDTO updateReview(UUID reviewId, ReviewRequestDTO requestDTO);
    void deleteReview(UUID reviewId);
    List<ReviewResponseDTO> getReviewsByPitch(UUID pitchId);
    Double getAverageRating(UUID pitchId);
}
