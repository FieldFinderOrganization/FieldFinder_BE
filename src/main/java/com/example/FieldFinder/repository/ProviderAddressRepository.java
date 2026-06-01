package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ProviderAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProviderAddressRepository extends JpaRepository<ProviderAddress, UUID> {
    List<ProviderAddress> findByProviderProviderId(UUID providerId);

    /** Địa chỉ thiếu toạ độ (cần geocode backfill cho "sân gần bạn"). */
    List<ProviderAddress> findByLatitudeIsNullOrLongitudeIsNull();
}
