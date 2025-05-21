package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.PitchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;
    private final PitchService pitchService;
    private final AIChat aiChat;
    public BookingController(BookingService bookingService, PitchService pitchService, AIChat aiChat) {
        this.bookingService = bookingService;
        this.pitchService = pitchService;
        this.aiChat = aiChat;
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
    @GetMapping("/slots/all")
    public ResponseEntity<List<PitchBookedSlotsDTO>> getAllBookedSlots(@RequestParam LocalDate date) {
        List<PitchBookedSlotsDTO> bookedSlots = bookingService.getAllBookedTimeSlots(date);
        return ResponseEntity.ok(bookedSlots);
    }
    @GetMapping("/available-pitches")
    public ResponseEntity<List<String>> getAvailablePitches(
            @RequestParam LocalDate date,
            @RequestParam List<Integer> slots) {
        List<String> availablePitches = bookingService.getAvailablePitches(date, slots);
        return ResponseEntity.ok(availablePitches);
    }
    @PostMapping("/ai-chat")
    public ResponseEntity<?> getAIChat(@RequestBody String userInput) {
        try {
            AIChat.BookingQuery response = aiChat.parseBookingInput(userInput);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to process AI request",
                            "details", e.getMessage())
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Request interrupted",
                            "details", e.getMessage())
            );
        }
    }

}