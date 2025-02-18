package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.BookingDto;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.mapper.BookingMapper;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.service.BookingService;
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
    public BookingDto createBooking(BookingDto bookingDTO) {
        Booking booking = BookingMapper.INSTANCE.toEntity(bookingDTO);
        return BookingMapper.INSTANCE.toDTO(bookingRepository.save(booking));
    }

    @Override
    public BookingDto getBookingById(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .map(BookingMapper.INSTANCE::toDTO)
                .orElse(null);
    }

    @Override
    public List<BookingDto> getAllBookings() {
        return bookingRepository.findAll()
                .stream()
                .map(BookingMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public BookingDto updateBooking(UUID bookingId, BookingDto bookingDTO) {
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
