package com.example.FieldFinder.service.impl;

package com.pitchbooking.application.service.impl;

import com.pitchbooking.application.dto.ReviewDTO;
import com.pitchbooking.application.entity.Review;
import com.pitchbooking.application.mapper.ReviewMapper;
import com.pitchbooking.application.repository.ReviewRepository;
import com.pitchbooking.application.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final ReviewRepository reviewRepository;

    @Override
    public ReviewDTO createReview(ReviewDTO reviewDTO) {
        Review review = ReviewMapper.INSTANCE.toEntity(reviewDTO);
        return ReviewMapper.INSTANCE.toDTO(reviewRepository.save(review));
    }

    @Override
    public List<ReviewDTO> getAllReviews() {
        return reviewRepository.findAll()
                .stream()
                .map(ReviewMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }
}
