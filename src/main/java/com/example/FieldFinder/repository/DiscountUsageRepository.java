package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.DiscountUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DiscountUsageRepository extends JpaRepository<DiscountUsage, UUID> {

    @Query("SELECT u FROM DiscountUsage u " +
            "JOIN FETCH u.userDiscount ud " +
            "JOIN FETCH ud.discount " +
            "WHERE u.orderId = :orderId AND u.reverted = false")
    List<DiscountUsage> findActiveByOrderId(@Param("orderId") Long orderId);

    @Query("SELECT u FROM DiscountUsage u " +
            "JOIN FETCH u.userDiscount ud " +
            "JOIN FETCH ud.discount " +
            "WHERE u.bookingId = :bookingId AND u.reverted = false")
    List<DiscountUsage> findActiveByBookingId(@Param("bookingId") UUID bookingId);
}
