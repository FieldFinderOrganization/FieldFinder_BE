package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }
}