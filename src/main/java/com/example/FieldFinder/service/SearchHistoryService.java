package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.SearchHistoryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface SearchHistoryService {
    List<SearchHistoryResponseDTO> getHistory(UUID userId, int limit);
    SearchHistoryResponseDTO upsert(UUID userId, String keyword);
    void delete(UUID userId, UUID id);
    void clear(UUID userId);
}
