package com.example.FieldFinder.service.impl;

package com.pitchbooking.application.service.impl;

import com.pitchbooking.application.dto.BookingDTO;
import com.pitchbooking.application.entity.Booking;
import com.pitchbooking.application.mapper.BookingMapper;
import com.pitchbooking.application.repository.BookingRepository;
import com.pitchbooking.application.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {
    private final BookingRepository bookingRepository;

    @Override
    public BookingDTO createBooking(BookingDTO bookingDTO) {
        Booking booking = BookingMapper.INSTANCE.toEntity(bookingDTO);
        return BookingMapper.INSTANCE.toDTO(bookingRepository.save(booking));
    }

    @Override
    public BookingDTO getBookingById(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .map(BookingMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<BookingDTO> getAllBookings() {
        return bookingRepository.findAll()
                .stream()
                .map(BookingMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public BookingDTO updateBooking(UUID bookingId, BookingDTO bookingDTO) {
        if (bookingRepository.existsById(bookingId)) {
            Booking booking = BookingMapper.INSTANCE.toEntity(bookingDTO);
            booking.setBookingId(bookingId);
            return BookingMapper.INSTANCE.toDTO(bookingRepository.save(booking));
        }
        return null;
    }

    @Override
    public void deleteBooking(UUID bookingId) {
        bookingRepository.deleteById(bookingId);
    }
}
