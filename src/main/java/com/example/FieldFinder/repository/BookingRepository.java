package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByUser(User user);

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
}
