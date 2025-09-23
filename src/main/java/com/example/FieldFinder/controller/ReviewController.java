package com.example.FieldFinder.controller;
import com.example.FieldFinder.dto.req.ReviewRequestDTO;
import com.example.FieldFinder.dto.res.ReviewResponseDTO;
import com.example.FieldFinder.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<ReviewResponseDTO> addReview(@RequestBody ReviewRequestDTO requestDTO) {
        return ResponseEntity.ok(reviewService.addReview(requestDTO));
    }

    @PutMapping("/{reviewId}")
    public ResponseEntity<ReviewResponseDTO> updateReview(@PathVariable UUID reviewId, @RequestBody ReviewRequestDTO requestDTO) {
        return ResponseEntity.ok(reviewService.updateReview(reviewId, requestDTO));
    }

    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> deleteReview(@PathVariable UUID reviewId) {
        reviewService.deleteReview(reviewId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pitch/{pitchId}")
    public ResponseEntity<List<ReviewResponseDTO>> getReviewsByPitch(@PathVariable UUID pitchId) {
        return ResponseEntity.ok(reviewService.getReviewsByPitch(pitchId));
    }

    @GetMapping("/pitch/{pitchId}/average-rating")
    public ResponseEntity<Double> getAverageRating(@PathVariable UUID pitchId) {
        return ResponseEntity.ok(reviewService.getAverageRating(pitchId));
    }
}
