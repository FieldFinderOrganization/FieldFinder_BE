package com.example.FieldFinder.repository;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.entity.BookingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingDetailRepository extends JpaRepository<BookingDetail, Long> {
    // List<BookingDetail> findByBookingId(Booking booking);

    List<BookingDetail> findByPitch_PitchIdAndBooking_BookingDate(UUID pitchId, LocalDate bookingDate);

    List<BookingDetail> findByBooking_BookingDate(LocalDate bookingDate);

    @Query("SELECT bd FROM BookingDetail bd WHERE bd.pitch.pitchId = :pitchId " +
            "AND bd.booking.bookingDate = :bookingDate " +
            "AND bd.booking.status NOT IN :excludedStatuses")
    List<BookingDetail> findByPitchAndDateExcludingStatuses(
            @Param("pitchId") UUID pitchId,
            @Param("bookingDate") LocalDate bookingDate,
            @Param("excludedStatuses") List<BookingStatus> excludedStatuses);

    @Query("SELECT bd FROM BookingDetail bd WHERE bd.booking.bookingDate = :bookingDate " +
            "AND bd.booking.status NOT IN :excludedStatuses")
    List<BookingDetail> findByBookingDateExcludingStatuses(
            @Param("bookingDate") LocalDate bookingDate,
            @Param("excludedStatuses") List<BookingStatus> excludedStatuses);

    boolean existsByPitch_PitchId(UUID pitchId);

}