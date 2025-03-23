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
}
