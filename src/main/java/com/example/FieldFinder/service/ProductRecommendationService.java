package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.SuggestedProductsResponseDTO;

import java.util.UUID;

public interface ProductRecommendationService {
    SuggestedProductsResponseDTO getSuggested(Long productId, UUID userId, int limit);
    java.util.List<com.example.FieldFinder.dto.res.ProductResponseDTO> getSuggestedFootballProducts(UUID userId, int limit);

    /** "Gợi ý cho bạn" — cá nhân hóa qua SASRec (recommendNext); cold-start/ML tắt ⇒ bán chạy. */
    java.util.List<com.example.FieldFinder.dto.res.ProductResponseDTO> getForYou(UUID userId, int limit);
}
