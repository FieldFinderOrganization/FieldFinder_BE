package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Pitch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface PitchRepository extends JpaRepository<Pitch, UUID> {
    List<Pitch> findByProviderAddressId(UUID providerAddressId);
}