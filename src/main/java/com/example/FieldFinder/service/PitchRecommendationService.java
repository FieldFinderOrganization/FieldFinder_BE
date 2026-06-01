package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.SuggestedPitchesResponseDTO;

import java.util.UUID;

public interface PitchRecommendationService {
    SuggestedPitchesResponseDTO getSuggested(UUID pitchId, UUID userId, Double lat, Double lng, int limit);
}
