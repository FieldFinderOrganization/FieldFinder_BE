package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "search_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_search_history_user_keyword",
                columnNames = {"user_id", "keyword"}),
        indexes = {
                @Index(name = "ix_search_history_user_time", columnList = "user_id,last_searched_at"),
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    @Column(name = "last_searched_at", nullable = false)
    private Instant lastSearchedAt;
}
