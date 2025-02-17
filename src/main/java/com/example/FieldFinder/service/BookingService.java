package com.example.FieldFinder.service;

package com.pitchbooking.application.service;

import com.pitchbooking.application.dto.BookingDTO;
import java.util.List;
import java.util.UUID;

public interface BookingService {
    BookingDTO createBooking(BookingDTO bookingDTO);
    BookingDTO getBookingById(UUID bookingId);
    List<BookingDTO> getAllBookings();
    BookingDTO updateBooking(UUID bookingId, BookingDTO bookingDTO);
    void deleteBooking(UUID bookingId);
}
