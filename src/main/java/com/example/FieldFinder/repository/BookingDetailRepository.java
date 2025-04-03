package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingDetailRepository extends JpaRepository<BookingDetail, Long> {
    List<BookingDetail> findByBookingId(Booking booking);
}