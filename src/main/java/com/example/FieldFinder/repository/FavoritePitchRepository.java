package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.FavoritePitch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface FavoritePitchRepository extends JpaRepository<FavoritePitch, UUID> {

    boolean existsByUserIdAndPitchId(UUID userId, UUID pitchId);

    @Modifying
    @Transactional
    void deleteByUserIdAndPitchId(UUID userId, UUID pitchId);

    List<FavoritePitch> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("select f.pitchId from FavoritePitch f where f.userId = :userId order by f.createdAt desc")
    List<UUID> findPitchIdsByUserId(UUID userId);
}
