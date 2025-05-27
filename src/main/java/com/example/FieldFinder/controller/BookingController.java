package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.PitchBookingResponse;
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
import java.util.stream.Collectors;

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
    public ResponseEntity<?> getAvailablePitchIdsFromAI(@RequestBody String userInput) {
        try {
            // Bước 1: Dùng AI để phân tích câu nhập
            AIChat.BookingQuery query = aiChat.parseBookingInput(userInput);

            if (query.bookingDate == null || query.slotList == null || query.slotList.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Không thể phân tích ngày và khung giờ từ câu nhập", "query", query)
                );
            }

            // Bước 2: Gọi service có sẵn
            LocalDate date = LocalDate.parse(query.bookingDate);
            List<String> pitchIds = bookingService.getAvailablePitches(date, query.slotList);

            // Bước 3: Chuyển đổi sang danh sách các đối tượng PitchBookingResponse
            List<PitchBookingResponse> responseList = pitchIds.stream()
                    .map(pitchId -> new PitchBookingResponse(pitchId, query.bookingDate, query.slotList))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responseList);

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Lỗi khi gửi yêu cầu tới AI", "details", e.getMessage())
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Yêu cầu bị gián đoạn", "details", e.getMessage())
            );
        }
    }



}