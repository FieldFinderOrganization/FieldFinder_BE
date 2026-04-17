package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Pitch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PitchRepository extends JpaRepository<Pitch, UUID>, JpaSpecificationExecutor<Pitch> {
    List<Pitch> findByProviderAddressProviderAddressId(UUID providerAddressId);

    Optional<Pitch> findById(UUID uuid);

    @Query("SELECT p.type, COUNT(p) FROM Pitch p GROUP BY p.type")
    List<Object[]> countByType();
}