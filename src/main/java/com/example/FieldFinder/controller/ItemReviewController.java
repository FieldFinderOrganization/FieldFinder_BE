package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.ItemReviewRequestDTO;
import com.example.FieldFinder.dto.req.ItemReviewUpdateRequestDTO;
import com.example.FieldFinder.dto.res.ItemReviewResponseDTO;
import com.example.FieldFinder.service.ItemReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@CrossOrigin("*")
@RequestMapping("/api/item-reviews")
@RequiredArgsConstructor
public class ItemReviewController {

    private final ItemReviewService itemReviewService;

    @PostMapping
    public ResponseEntity<ItemReviewResponseDTO> createReview(@RequestBody ItemReviewRequestDTO request) {
        return ResponseEntity.ok(itemReviewService.createReview(request));
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<List<ItemReviewResponseDTO>> getReviewsByProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(itemReviewService.getReviewsByProduct(productId));
    }

    @PutMapping("/{reviewId}")
    public ResponseEntity<ItemReviewResponseDTO> updateReview(
            @PathVariable Long reviewId,
            @RequestBody ItemReviewUpdateRequestDTO request
    ) {
        return ResponseEntity.ok(itemReviewService.updateReview(reviewId, request));
    }

    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> deleteReview(@PathVariable Long reviewId) {
        itemReviewService.deleteReview(reviewId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ItemReviewResponseDTO>> getReviewsByUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(itemReviewService.getReviewsByUser(userId));
    }

    @GetMapping
    public ResponseEntity<List<ItemReviewResponseDTO>> getAllItemReviews() {
        return ResponseEntity.ok(itemReviewService.getAllItemReviews());
    }
}
