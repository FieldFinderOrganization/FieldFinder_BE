package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.dto.ReviewDto;
import com.example.FieldFinder.entity.Review;
import com.example.FieldFinder.mapper.ReviewMapper;
import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReviewServiceImpl implements ReviewService {
    private final ReviewRepository reviewRepository;
    public ReviewServiceImpl(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }
    @Override
    public ReviewDto createReview(ReviewDto reviewDTO) {
        Review review = ReviewMapper.INSTANCE.toEntity(reviewDTO);
        return ReviewMapper.INSTANCE.toDTO(reviewRepository.save(review));
    }

    @Override
    public List<ReviewDto> getAllReviews() {
        return reviewRepository.findAll()
                .stream()
                .map(ReviewMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public ReviewDto getReviewById(UUID id) {
        return null;
    }

    @Override
    public ReviewDto updateReview(UUID id, ReviewDto review) {
        return null;
    }

    @Override
    public void deleteReview(UUID id) {

    }
}
