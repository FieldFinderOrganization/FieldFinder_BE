package com.example.FieldFinder.service;


import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.BookingResponseDTO;
import com.example.FieldFinder.entity.Booking;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

public interface BookingService {
    Booking createBooking(BookingRequestDTO bookingRequest);
    ResponseEntity<String> updateBookingStatus(UUID bookingId, String status);
    List<BookingResponseDTO> getBookingsByUser(UUID userId);
    Booking getBookingDetails(UUID bookingId);
    void cancelBooking(UUID bookingId);
    BigDecimal calculateTotalPrice(UUID bookingId);
    List<Integer> getBookedTimeSlots(UUID pitchId, LocalDate bookingDate);
    List<BookingResponseDTO> getAllBookings();
    List<PitchBookedSlotsDTO> getAllBookedTimeSlots(LocalDate date);

    List<String> getAvailablePitches(LocalDate date, List<Integer> slots, String pitchType );
    ResponseEntity<String> updatePaymentStatus(UUID bookingId, String paymentStatus);

    boolean isPitchAvailable(UUID pitchId, LocalDate date, List<Integer> slotList, String name);
}