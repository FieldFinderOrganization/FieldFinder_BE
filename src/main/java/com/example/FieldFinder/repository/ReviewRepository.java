package com.example.FieldFinder.repository;


import com.example.FieldFinder.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {
    List<Review> findByPitch_PitchId(UUID pitchId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.pitch.pitchId = :pitchId")
    Double findAverageRatingByPitchId(UUID pitchId);

    @Query("SELECT AVG(r.rating) FROM Review r")
    Double findOverallAverageRating();

    @Query("SELECT r.rating, COUNT(r) FROM Review r GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> countByRating();

    List<Review> findTop10ByOrderByCreatedAtDesc();

    long countByRatingEquals(int rating);
}