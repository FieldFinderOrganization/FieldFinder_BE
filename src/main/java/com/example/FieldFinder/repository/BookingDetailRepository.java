package com.example.FieldFinder.repository;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingDetailRepository extends JpaRepository<BookingDetail, Long> {
    //List<BookingDetail> findByBookingId(Booking booking);

    List<BookingDetail> findByPitch_PitchIdAndBooking_BookingDate(UUID pitchId, LocalDate bookingDate);



}