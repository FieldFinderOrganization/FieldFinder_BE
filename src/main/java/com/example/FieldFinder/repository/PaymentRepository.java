package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByUser_UserId(UUID userId);
    List<Payment> findByPaymentStatus(Booking.PaymentStatus status);
    Optional<Payment> findByTransactionId(String transactionId);
}
