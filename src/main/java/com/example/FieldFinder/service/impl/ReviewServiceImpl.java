package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.entity.Review;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.service.ReviewService;
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
}
