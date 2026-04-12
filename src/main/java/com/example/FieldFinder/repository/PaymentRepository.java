package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Payment;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByUser_UserId(UUID userId);
    //    List<Payment> findByPaymentStatus(Booking.PaymentStatus status);
    Optional<Payment> findByTransactionId(String transactionId);

    @Query("SELECT p.paymentMethod FROM Payment p WHERE p.booking.bookingId = :id")
    Optional<PaymentMethod> findFirstPaymentMethodByBookingId(@Param("id") UUID bookingId);

    @Query("SELECT p FROM Payment p WHERE p.booking.bookingId IN :ids")
    List<Payment> findAllByBookingIds(@Param("ids") List<UUID> ids);

    Optional<Payment> findFirstByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId);
}
