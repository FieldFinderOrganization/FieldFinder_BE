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
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin("*")
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

    private String getSessionId() {
        return "session_" + UUID.randomUUID().toString();
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
    public ResponseEntity<?> getAvailablePitchFromAI(
            @RequestBody String userInput,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        try {
            // Tạo sessionId mới nếu không có từ header
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
            }

            AIChat.BookingQuery query = aiChat.parseBookingInput(userInput, sessionId);

            // Xử lý câu hỏi về sân có đánh giá cao nhất
            if (query.message != null && query.message.contains("đánh giá cao nhất")) {
                PitchResponseDTO selectedPitch = (PitchResponseDTO) query.data.get("selectedPitch");
                if (selectedPitch != null) {
                    PitchBookingResponse response = new PitchBookingResponse(
                            selectedPitch.getPitchId(),
                            selectedPitch.getName(),
                            selectedPitch.getPrice(),
                            selectedPitch.getDescription(),
                            null,
                            new ArrayList<>(),
                            selectedPitch.getType()
                    );
                    return ResponseEntity.ok()
                            .header("X-Session-Id", sessionId)
                            .body(List.of(response));
                } else {
                    return ResponseEntity.ok()
                            .header("X-Session-Id", sessionId)
                            .body(Map.of(
                                    "message", "Không tìm thấy sân nào có đánh giá.",
                                    "data", new HashMap<>()
                            ));
                }
            }

            // Xử lý các trường hợp đặc biệt
            if (query.message != null) {
                // Xử lý câu hỏi về giá rẻ nhất hoặc mắc nhất
                if (query.message.contains("giá rẻ nhất") || query.message.contains("giá mắc nhất")) {
                    PitchResponseDTO selectedPitch = (PitchResponseDTO) query.data.get("selectedPitch");
                    if (selectedPitch != null) {
                        PitchBookingResponse response = new PitchBookingResponse(
                                selectedPitch.getPitchId(),
                                selectedPitch.getName(),
                                selectedPitch.getPrice(),
                                selectedPitch.getDescription(),
                                null,
                                new ArrayList<>(),
                                selectedPitch.getType()
                        );
                        return ResponseEntity.ok()
                                .header("X-Session-Id", sessionId)
                                .body(List.of(response));
                    } else {
                        return ResponseEntity.ok()
                                .header("X-Session-Id", sessionId)
                                .body(Map.of(
                                        "message", "Không tìm thấy sân nào.",
                                        "data", new HashMap<>()
                                ));
                    }
                }
                // Xử lý yêu cầu đặt "sân này"
                else if (userInput.contains("sân này")) {
                    PitchResponseDTO selectedPitch = (PitchResponseDTO) query.data.get("selectedPitch");
                    if (selectedPitch == null) {
                        return ResponseEntity.ok()
                                .header("X-Session-Id", sessionId)
                                .body(Map.of(
                                        "message", "Không tìm thấy sân phù hợp. Vui lòng chọn sân trước.",
                                        "data", new HashMap<>()
                                ));
                    }

                    if (query.bookingDate != null && !query.slotList.isEmpty()) {
                        LocalDate date = LocalDate.parse(query.bookingDate);
                        boolean isAvailable = bookingService.isPitchAvailable(
                                selectedPitch.getPitchId(),
                                date,
                                query.slotList,
                                selectedPitch.getType().name()
                        );

                        if (isAvailable) {
                            PitchBookingResponse response = new PitchBookingResponse(
                                    selectedPitch.getPitchId(),
                                    selectedPitch.getName(),
                                    selectedPitch.getPrice(),
                                    selectedPitch.getDescription(),
                                    query.bookingDate,
                                    query.slotList,
                                    selectedPitch.getType()
                            );
                            return ResponseEntity.ok()
                                    .header("X-Session-Id", sessionId)
                                    .body(List.of(response));
                        } else {
                            return ResponseEntity.ok()
                                    .header("X-Session-Id", sessionId)
                                    .body(Map.of(
                                            "message", "Sân " + selectedPitch.getName() + " không trống vào thời gian bạn chọn.",
                                            "data", new HashMap<>()
                                    ));
                        }
                    } else {
                        return ResponseEntity.ok()
                                .header("X-Session-Id", sessionId)
                                .body(Map.of(
                                        "message", "Vui lòng cung cấp ngày và giờ để đặt sân.",
                                        "data", new HashMap<>()
                                ));
                    }
                }

                // Trả về phản hồi mặc định cho các câu hỏi khác
                Map<String, Object> response = new HashMap<>();
                response.put("message", query.message);
                response.put("data", query.data);
                return ResponseEntity.ok()
                        .header("X-Session-Id", sessionId)
                        .body(response);
            }

            // Xử lý yêu cầu đặt sân thông thường
            if (query.bookingDate == null || query.slotList == null || query.slotList.isEmpty()) {
                return ResponseEntity.badRequest()
                        .header("X-Session-Id", sessionId)
                        .body(Map.of(
                                "error", "Không thể phân tích ngày và khung giờ từ câu nhập",
                                "query", query
                        ));
            }

            LocalDate date = LocalDate.parse(query.bookingDate);
            List<String> pitchIds = bookingService.getAvailablePitches(
                    date, query.slotList, query.pitchType);

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
                                pitchDTO.getType()
                        );
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok()
                    .header("X-Session-Id", sessionId)
                    .body(responseList);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Lỗi khi gửi yêu cầu tới AI",
                            "details", e.getMessage()
                    ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "error", "Yêu cầu bị gián đoạn",
                            "details", e.getMessage()
                    ));
        }
    }

    private String formatPitchType(String pitchType) {
        switch (pitchType) {
            case "FIVE_A_SIDE": return "sân 5 người";
            case "SEVEN_A_SIDE": return "sân 7 người";
            case "ELEVEN_A_SIDE": return "sân 11 người";
            default: return "tất cả sân";
        }
    }

    @PutMapping("/{bookingId}/status")
    public ResponseEntity<String> updateBookingStatus(
            @PathVariable UUID bookingId,
            @RequestParam("status") String status) {
        return bookingService.updateBookingStatus(bookingId, status);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<BookingResponseDTO>> getBookingsByUser(@PathVariable UUID userId) {
        List<BookingResponseDTO> bookings = bookingService.getBookingsByUser(userId);
        return ResponseEntity.ok(bookings);
    }

    @GetMapping
    public ResponseEntity<List<BookingResponseDTO>> getAllBookings() {
        return ResponseEntity.ok(bookingService.getAllBookings());
    }
}