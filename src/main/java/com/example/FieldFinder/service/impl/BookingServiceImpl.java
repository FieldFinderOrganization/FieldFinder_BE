package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.service.BookingService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingServiceImpl implements BookingService {
    private final BookingRepository bookingRepository;
    public BookingServiceImpl(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }
}
