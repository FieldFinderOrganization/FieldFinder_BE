package com.example.FieldFinder.service;
import com.example.FieldFinder.dto.ReviewDto;

import java.util.List;
import java.util.UUID;

public interface ReviewService {
    ReviewDto createReview(ReviewDto reviewDTO);
    List<ReviewDto> getAllReviews();

    ReviewDto getReviewById(UUID id);

    ReviewDto updateReview(UUID id, ReviewDto review);

    void deleteReview(UUID id);
}
