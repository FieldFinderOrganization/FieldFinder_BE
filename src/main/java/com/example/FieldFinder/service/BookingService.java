package com.example.FieldFinder.service;


import com.example.FieldFinder.dto.BookingDto;

import java.util.List;
import java.util.UUID;

public interface BookingService {
    BookingDto createBooking(BookingDto bookingDTO);
    BookingDto getBookingById(UUID bookingId);
    List<BookingDto> getAllBookings();
    BookingDto updateBooking(UUID bookingId, BookingDto bookingDTO);
    void deleteBooking(UUID bookingId);
}
