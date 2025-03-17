package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.ProviderAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProviderAddressRepository extends JpaRepository<ProviderAddress, Long> {
    List<ProviderAddress> findByProvider_ProviderId(Long providerId);
}
