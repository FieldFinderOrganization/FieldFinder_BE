package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.SearchHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SearchHistoryRepository extends JpaRepository<SearchHistory, UUID> {

    List<SearchHistory> findByUser_UserIdOrderByLastSearchedAtDesc(UUID userId, Pageable pageable);

    List<SearchHistory> findByUser_UserIdOrderByLastSearchedAtDesc(UUID userId);

    Optional<SearchHistory> findByUser_UserIdAndKeyword(UUID userId, String keyword);

    long countByUser_UserId(UUID userId);

    @Modifying
    @Query("DELETE FROM SearchHistory s WHERE s.user.userId = :userId")
    void deleteAllByUser(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM SearchHistory s WHERE s.id IN :ids")
    void deleteAllByIdIn(@Param("ids") List<UUID> ids);
}
