package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.BookingResponseDTO;
import com.example.FieldFinder.dto.res.PitchBookingResponse;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
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
    @PutMapping("/{bookingId}/payment-status")
    public ResponseEntity<String> updatePaymentStatus(
            @PathVariable UUID bookingId,
            @RequestParam("status") String status) {
        return bookingService.updatePaymentStatus(bookingId, status);
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
            @RequestParam List<Integer> slots,
            @RequestParam String pitchType) {
        List<String> availablePitches = bookingService.getAvailablePitches(date, slots, pitchType);
        return ResponseEntity.ok(availablePitches);
    }
    @PostMapping("/ai-chat")
    public ResponseEntity<?> getAvailablePitchFromAI(@RequestBody String userInput) {
        try {
            // Step 1: Use AI to parse user input
            AIChat.BookingQuery query = aiChat.parseBookingInput(userInput);

            if (query.bookingDate == null || query.slotList == null || query.slotList.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Không thể phân tích ngày và khung giờ từ câu nhập", "query", query)
                );
            }

            // Step 2: Call service to get available pitch IDs
            LocalDate date = LocalDate.parse(query.bookingDate);
            List<String> pitchIds = bookingService.getAvailablePitches(date, query.slotList, query.pitchType);

            // Step 3: Fetch pitch details and prepare response list
            List<PitchBookingResponse> responseList = pitchIds.stream()
                    .map(pitchIdStr -> {
                        UUID pitchId = UUID.fromString(pitchIdStr);
                        PitchResponseDTO pitchDTO = pitchService.getPitchById(pitchId);

                        return new PitchBookingResponse(
                                pitchDTO.getPitchId(),
                                pitchDTO.getName(),
                                pitchDTO.getPrice(),
                                pitchDTO.getDescription(),
                                query.bookingDate,
                                query.slotList,
                                query.pitchType
                        );
                    })
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
    // Cập nhật trạng thái đặt sân
    @PutMapping("/{bookingId}/status")
    public ResponseEntity<String> updateBookingStatus(
            @PathVariable UUID bookingId,
            @RequestParam("status") String status) {
        return bookingService.updateBookingStatus(bookingId, status);
    }


    // Lấy danh sách đặt sân theo User ID
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<BookingResponseDTO>> getBookingsByUser(@PathVariable UUID userId) {
        List<BookingResponseDTO> bookings = bookingService.getBookingsByUser(userId);
        return ResponseEntity.ok(bookings);
    }


}