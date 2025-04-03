package com.example.FieldFinder.service;


import java.util.List;
import java.util.UUID;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.entity.Booking;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BookingService {
    Booking createBooking(BookingRequestDTO bookingRequest);
    Booking updateBookingStatus(UUID bookingId, String status);
    List<Booking> getBookingsByUser(UUID userId);
    Booking getBookingDetails(UUID bookingId);
    void cancelBooking(UUID bookingId);
    BigDecimal calculateTotalPrice(UUID bookingId);
}