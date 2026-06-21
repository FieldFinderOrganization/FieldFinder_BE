package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.PaymentSecurity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSecurityRepository extends JpaRepository<PaymentSecurity, UUID> {
    Optional<PaymentSecurity> findByUserId(UUID userId);
}
