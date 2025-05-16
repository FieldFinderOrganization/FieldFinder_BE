package com.example.FieldFinder.controller;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }
    @PostMapping
    public ResponseEntity<Booking> createBooking(@RequestBody BookingRequestDTO bookingRequestDTO) {
        Booking booking = bookingService.createBooking(bookingRequestDTO);
        return ResponseEntity.ok(booking);
    }
    @GetMapping("/slots/{pitchId}")
    public ResponseEntity<List<Integer>> getBookedSlots(
            @PathVariable UUID pitchId,
            @RequestParam LocalDate date) {
        List<Integer> bookedSlots = bookingService.getBookedTimeSlots(pitchId, date);
        return ResponseEntity.ok(bookedSlots);
    }

}