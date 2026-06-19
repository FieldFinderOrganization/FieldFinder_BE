package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.ProviderDebtStatus;
import com.example.FieldFinder.entity.ProviderDebt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderDebtRepository extends JpaRepository<ProviderDebt, UUID> {

    boolean existsBySourceBookingId(String sourceBookingId);

    Optional<ProviderDebt> findBySourceBookingId(String sourceBookingId);

    List<ProviderDebt> findByProvider_ProviderIdAndStatus(UUID providerId, ProviderDebtStatus status);
}
