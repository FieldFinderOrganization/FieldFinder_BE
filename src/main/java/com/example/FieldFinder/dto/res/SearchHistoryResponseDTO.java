package com.example.FieldFinder.dto.res;

import com.example.FieldFinder.entity.SearchHistory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class SearchHistoryResponseDTO {
    private UUID id;
    private String keyword;
    private Instant lastSearchedAt;

    public static SearchHistoryResponseDTO fromEntity(SearchHistory entity) {
        return new SearchHistoryResponseDTO(
                entity.getId(),
                entity.getKeyword(),
                entity.getLastSearchedAt()
        );
    }
}
