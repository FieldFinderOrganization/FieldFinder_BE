package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.req.ItemReviewRequestDTO;
import com.example.FieldFinder.dto.req.ItemReviewUpdateRequestDTO;
import com.example.FieldFinder.dto.res.ItemReviewResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ItemReviewService {
    ItemReviewResponseDTO createReview(ItemReviewRequestDTO request);
    ItemReviewResponseDTO updateReview(Long reviewId, ItemReviewUpdateRequestDTO request);
    void deleteReview(Long reviewId);
    List<ItemReviewResponseDTO> getReviewsByProduct(Long productId);
    List<ItemReviewResponseDTO> getReviewsByUser(UUID userId);
    List<ItemReviewResponseDTO> getAllItemReviews();
}
