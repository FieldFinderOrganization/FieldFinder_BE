package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.res.SearchHistoryResponseDTO;
import com.example.FieldFinder.entity.SearchHistory;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.SearchHistoryRepository;
import com.example.FieldFinder.repository.UserRepository;
import com.example.FieldFinder.service.SearchHistoryService;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SearchHistoryServiceImpl implements SearchHistoryService {

    private static final int MAX_KEYWORDS = 10;

    private final SearchHistoryRepository repository;
    private final UserRepository userRepository;

    public SearchHistoryServiceImpl(SearchHistoryRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Override
    public List<SearchHistoryResponseDTO> getHistory(UUID userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findByUser_UserIdOrderByLastSearchedAtDesc(userId, PageRequest.of(0, safeLimit))
                .stream()
                .map(SearchHistoryResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SearchHistoryResponseDTO upsert(UUID userId, String keyword) {
        String raw = keyword == null ? "" : keyword.trim();
        if (raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keyword không được để trống");
        }
        final String trimmed = raw.length() > 255 ? raw.substring(0, 255) : raw;

        SearchHistory entity = repository.findByUser_UserIdAndKeyword(userId, trimmed)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
                    return SearchHistory.builder()
                            .user(user)
                            .keyword(trimmed)
                            .build();
                });
        entity.setLastSearchedAt(Instant.now());
        SearchHistory saved = repository.save(entity);

        long count = repository.countByUser_UserId(userId);
        if (count > MAX_KEYWORDS) {
            List<SearchHistory> all = repository.findByUser_UserIdOrderByLastSearchedAtDesc(userId);
            List<UUID> toRemove = all.stream()
                    .skip(MAX_KEYWORDS)
                    .map(SearchHistory::getId)
                    .collect(Collectors.toList());
            if (!toRemove.isEmpty()) repository.deleteAllByIdIn(toRemove);
        }
        return SearchHistoryResponseDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID id) {
        SearchHistory entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search history not found"));
        if (!entity.getUser().getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền xóa");
        }
        repository.delete(entity);
    }

    @Override
    @Transactional
    public void clear(UUID userId) {
        repository.deleteAllByUser(userId);
    }
}
