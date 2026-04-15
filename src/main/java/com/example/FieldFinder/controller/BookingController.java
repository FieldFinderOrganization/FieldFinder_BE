package com.example.FieldFinder.controller;

import com.example.FieldFinder.ai.AIChat;
import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.dto.req.PitchBookedSlotsDTO;
import com.example.FieldFinder.dto.res.BookingResponseDTO;
import com.example.FieldFinder.dto.res.PitchBookingResponse;
import com.example.FieldFinder.dto.res.PitchResponseDTO;
import com.example.FieldFinder.dto.res.ProviderBookingResponseDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.PitchService;
import com.example.FieldFinder.service.RedisService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
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
    private final RedisService redisService;

    public BookingController(BookingService bookingService, PitchService pitchService, AIChat aiChat, RedisService redisService) {
        this.bookingService = bookingService;
        this.pitchService = pitchService;
        this.aiChat = aiChat;
        this.redisService = redisService;
    }

    private UUID getUserIdFromAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals("anonymousUser")) {
            return null;
        }
        try {
            Object principal = authentication.getPrincipal();
            String email = null;
            if (principal instanceof UserDetails) {
                email = ((UserDetails) principal).getUsername();
            } else if (principal instanceof String) {
                email = (String) principal;
            }
            if (email != null) {
                return redisService.getUserIdByEmail(email);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String getSessionId() {
        return "session_" + UUID.randomUUID().toString();
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> createBooking(@RequestBody BookingRequestDTO bookingRequestDTO) {
        Booking booking = bookingService.createBooking(bookingRequestDTO);

        Map<String, Object> response = new HashMap<>();
        response.put("bookingId", booking.getBookingId());
        response.put("status", booking.getStatus());
        response.put("message", "Đặt sân thành công!");

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{bookingId}/payment-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<List<PitchBookedSlotsDTO>> getAllBookedSlots(@RequestParam LocalDate date) {
        List<PitchBookedSlotsDTO> bookedSlots = bookingService.getAllBookedTimeSlots(date);
        return ResponseEntity.ok(bookedSlots);
    }

    @GetMapping("/available-pitches")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<String>> getAvailablePitches(
            @RequestParam LocalDate date,
            @RequestParam List<Integer> slots,
            @RequestParam String pitchType) {
        List<String> availablePitches = bookingService.getAvailablePitches(date, slots, pitchType);
        return ResponseEntity.ok(availablePitches);
    }

    @PostMapping("/ai-chat")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getAvailablePitchFromAI(
            @RequestBody String userInput,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = getSessionId();
            }

            AIChat.BookingQuery query = aiChat.parseBookingInput(userInput, sessionId);

            Map<String, Object> responseHeaders = new HashMap<>();
            responseHeaders.put("X-Session-Id", sessionId);

            if (query.message != null) {
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
                } else if (userInput.contains("sân này")) {
                    PitchResponseDTO selectedPitch = (PitchResponseDTO) query.data.get("selectedPitch");
                    if (selectedPitch == null) {
                        selectedPitch = aiChat.findPitchByContext(userInput);
                    }

                    if (selectedPitch != null && query.bookingDate != null && !query.slotList.isEmpty()) {
                        LocalDate date = LocalDate.parse(query.bookingDate);
                        List<String> availablePitches = bookingService.getAvailablePitches(
                                date, query.slotList, selectedPitch.getType().name());

                        if (availablePitches.contains(selectedPitch.getPitchId().toString())) {
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
                                            "message", "Sân này không trống vào thời gian bạn chọn.",
                                            "data", new HashMap<>()
                                    ));
                        }
                    } else {
                        return ResponseEntity.ok()
                                .header("X-Session-Id", sessionId)
                                .body(Map.of(
                                        "message", selectedPitch == null ?
                                                "Không tìm thấy sân phù hợp. Vui lòng chọn sân trước." :
                                                "Vui lòng cung cấp ngày và giờ để đặt sân.",
                                        "data", new HashMap<>()
                                ));
                    }
                }

                Map<String, Object> response = new HashMap<>();
                response.put("message", query.message);
                response.put("data", query.data);
                return ResponseEntity.ok()
                        .header("X-Session-Id", sessionId)
                        .body(response);
            }

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

    @PutMapping("/{bookingId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<String> updateBookingStatus(
            @PathVariable UUID bookingId,
            @RequestParam("status") String status) {
        return bookingService.updateBookingStatus(bookingId, status);
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ProviderBookingResponseDTO>> getBookingsByUser(@PathVariable UUID userId) {
        List<ProviderBookingResponseDTO> bookings = bookingService.getBookingsByUser(userId);
        return ResponseEntity.ok(bookings);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<List<BookingResponseDTO>> getAllBookings() {
        return ResponseEntity.ok(bookingService.getAllBookings());
    }

    @GetMapping("/provider/{providerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ResponseEntity<List<ProviderBookingResponseDTO>> getBookingsByProvider(@PathVariable UUID providerId) {

        List<ProviderBookingResponseDTO> response = bookingService.getBookingsByProviderId(providerId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{bookingId}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> cancelBooking(@PathVariable UUID bookingId, Authentication authentication) {
        UUID userId = getUserIdFromAuth(authentication);

        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message","Không xác định được người dùng!"));
        }

        bookingService.cancelBookingByUser(bookingId, userId);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Hủy đặt sân thành công.");
        response.put("bookingId", bookingId);

        return ResponseEntity.ok(response);
    }
}