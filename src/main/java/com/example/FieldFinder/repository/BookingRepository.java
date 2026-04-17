package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByUser(User user);
    List<Booking> findAllByStatus(BookingStatus status);

    @Query("SELECT DISTINCT b FROM Booking b " +
            "JOIN b.bookingDetails bd " +
            "JOIN bd.pitch p " +
            "JOIN p.providerAddress pa " +
            "JOIN pa.provider prov " +
            "WHERE prov.providerId = :providerId")
    List<Booking> findByProviderId(@Param("providerId") UUID providerId);

    @Query("SELECT b FROM Booking b " +
            "LEFT JOIN FETCH b.bookingDetails bd " +
            "LEFT JOIN FETCH bd.pitch p " +
            "LEFT JOIN FETCH p.providerAddress pa " +
            "LEFT JOIN FETCH pa.provider pr " +
            "LEFT JOIN FETCH pr.user pu " +
            "WHERE b.user = :user")
    List<Booking> findByUserWithDetails(@Param("user") User user);

    @Query("SELECT DISTINCT b FROM Booking b " +
            "LEFT JOIN FETCH b.bookingDetails bd " +
            "LEFT JOIN FETCH bd.timeSlot ts " +
            "WHERE b.status = com.example.FieldFinder.Enum.BookingStatus.PENDING")
    List<Booking> findAllPendingWithDetails();

    long countByBookingDate(LocalDate date);

    long countByStatus(BookingStatus status);

    @Query("SELECT COALESCE(SUM(b.totalPrice), 0) FROM Booking b WHERE b.paymentStatus = :status")
    BigDecimal sumTotalPriceByPaymentStatus(@Param("status") PaymentStatus status);

    @Query("SELECT COALESCE(SUM(b.totalPrice), 0) FROM Booking b WHERE b.paymentStatus = :status AND b.bookingDate BETWEEN :startDate AND :endDate")
    BigDecimal sumTotalPriceByPaymentStatusAndDateRange(@Param("status") PaymentStatus status, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT b FROM Booking b LEFT JOIN FETCH b.user LEFT JOIN FETCH b.bookingDetails bd LEFT JOIN FETCH bd.pitch ORDER BY b.createdAt DESC")
    List<Booking> findTopRecentBookings(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT b.bookingDate, COALESCE(SUM(b.totalPrice), 0) FROM Booking b WHERE b.paymentStatus = com.example.FieldFinder.Enum.PaymentStatus.PAID AND b.bookingDate BETWEEN :startDate AND :endDate GROUP BY b.bookingDate ORDER BY b.bookingDate")
    List<Object[]> findRevenueByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT DAYOFWEEK(b.bookingDate), COUNT(b) FROM Booking b WHERE b.bookingDate BETWEEN :startDate AND :endDate GROUP BY DAYOFWEEK(b.bookingDate)")
    List<Object[]> countBookingsByDayOfWeek(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT b FROM Booking b LEFT JOIN FETCH b.user LEFT JOIN FETCH b.bookingDetails bd LEFT JOIN FETCH bd.pitch ORDER BY b.createdAt DESC")
    Page<Booking> findAllWithDetails(Pageable pageable);

    @Query("SELECT b FROM Booking b LEFT JOIN FETCH b.user LEFT JOIN FETCH b.bookingDetails bd LEFT JOIN FETCH bd.pitch WHERE b.status = :status ORDER BY b.createdAt DESC")
    Page<Booking> findAllByStatusWithDetails(@Param("status") BookingStatus status, Pageable pageable);
}