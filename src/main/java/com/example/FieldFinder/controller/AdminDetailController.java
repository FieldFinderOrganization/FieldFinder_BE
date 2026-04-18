package com.example.FieldFinder.controller;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDetailController {

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final OrderRepository orderRepository;
    private final PitchRepository pitchRepository;
    private final ReviewRepository reviewRepository;

    public AdminDetailController(UserRepository userRepository,
                                 BookingRepository bookingRepository,
                                 OrderRepository orderRepository,
                                 PitchRepository pitchRepository,
                                 ReviewRepository reviewRepository) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.orderRepository = orderRepository;
        this.pitchRepository = pitchRepository;
        this.reviewRepository = reviewRepository;
    }

    // ── USERS ──────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<User> userPage = search.isBlank()
                ? userRepository.findAll(pageable)
                : userRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(search, search, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (User u : userPage.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", u.getUserId().toString());
            item.put("name", u.getName());
            item.put("email", u.getEmail());
            item.put("phone", u.getPhone() != null ? u.getPhone() : "");
            item.put("role", u.getRole().name());
            item.put("status", u.getStatus().name());
            item.put("lastLoginAt", u.getLastLoginAt() != null
                    ? u.getLastLoginAt().toInstant().toString() : null);
            item.put("createdAt", u.getCreatedAt() != null
                    ? u.getCreatedAt().toInstant().toString() : null);
            content.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("currentPage", page);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/users/stats")
    public ResponseEntity<Map<String, Object>> getUserStats() {
        List<Map<String, Object>> byRole = new ArrayList<>();
        for (Object[] row : userRepository.countByRole()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", row[0].toString());
            m.put("count", ((Number) row[1]).longValue());
            byRole.add(m);
        }

        List<Map<String, Object>> byStatus = new ArrayList<>();
        for (Object[] row : userRepository.countByStatus()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", row[0].toString());
            m.put("count", ((Number) row[1]).longValue());
            byStatus.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byRole", byRole);
        result.put("byStatus", byStatus);
        result.put("total", userRepository.count());
        return ResponseEntity.ok(result);
    }

    // ── BOOKINGS ───────────────────────────────────────────────────────────────

    @GetMapping("/bookings")
    public ResponseEntity<Map<String, Object>> getBookings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        PageRequest pageable = PageRequest.of(page, size);
        Page<Booking> bookingPage;

        if (status != null && !status.isBlank()) {
            BookingStatus bs = BookingStatus.valueOf(status.toUpperCase());
            bookingPage = bookingRepository.findAllByStatusWithDetails(bs, pageable);
        } else {
            bookingPage = bookingRepository.findAllWithDetails(pageable);
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<Map<String, Object>> content = new ArrayList<>();
        for (Booking b : bookingPage.getContent()) {
            String pitchName = b.getBookingDetails().stream()
                    .filter(bd -> bd.getPitch() != null)
                    .map(bd -> bd.getPitch().getName())
                    .findFirst().orElse("—");

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bookingId", b.getBookingId().toString());
            item.put("userName", b.getUser() != null ? b.getUser().getName() : "—");
            item.put("pitchName", pitchName);
            item.put("bookingDate", b.getBookingDate().toString());
            item.put("totalPrice", b.getTotalPrice());
            item.put("paymentStatus", b.getPaymentStatus().name());
            item.put("status", b.getStatus().name());
            item.put("createdAt", b.getCreatedAt() != null
                    ? b.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "");
            content.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", bookingPage.getTotalElements());
        result.put("totalPages", bookingPage.getTotalPages());
        result.put("currentPage", page);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bookings/stats")
    public ResponseEntity<Map<String, Object>> getBookingStats() {
        long confirmed = bookingRepository.countByStatus(BookingStatus.CONFIRMED);
        long canceled  = bookingRepository.countByStatus(BookingStatus.CANCELED);
        long pending   = bookingRepository.countByStatus(BookingStatus.PENDING);
        long total     = confirmed + canceled + pending;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", confirmed);
        result.put("canceled",  canceled);
        result.put("pending",   pending);
        result.put("total",     total);
        return ResponseEntity.ok(result);
    }

    // ── ORDERS ─────────────────────────────────────────────────────────────────

    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        PageRequest pageable = PageRequest.of(page, size);
        Page<Order> orderPage;

        if (status != null && !status.isBlank()) {
            OrderStatus os = OrderStatus.valueOf(status.toUpperCase());
            orderPage = orderRepository.findByStatusOrderByCreatedAtDesc(os, pageable);
        } else {
            orderPage = orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (Order o : orderPage.getContent()) {
            int itemCount = o.getItems() != null ? o.getItems().size() : 0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", o.getOrderId());
            item.put("userName", o.getUser() != null ? o.getUser().getName() : "—");
            item.put("totalAmount", o.getTotalAmount() != null ? o.getTotalAmount() : 0.0);
            item.put("status", o.getStatus().name());
            item.put("itemCount", itemCount);
            item.put("createdAt", o.getCreatedAt() != null
                    ? o.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "");
            content.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", orderPage.getTotalElements());
        result.put("totalPages", orderPage.getTotalPages());
        result.put("currentPage", page);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/orders/stats")
    public ResponseEntity<List<Map<String, Object>>> getOrderStats() {
        List<Map<String, Object>> stats = new ArrayList<>();
        for (OrderStatus s : OrderStatus.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", s.name());
            m.put("count", orderRepository.countByStatus(s));
            stats.add(m);
        }
        return ResponseEntity.ok(stats);
    }

    // ── PITCHES ────────────────────────────────────────────────────────────────

    @GetMapping("/pitches")
    public ResponseEntity<Map<String, Object>> getPitches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<Pitch> pitchPage = pitchRepository.findAll(pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (Pitch p : pitchPage.getContent()) {
            String providerName = "—";
            try {
                providerName = p.getProviderAddress().getProvider().getUser().getName();
            } catch (Exception ignored) {}

            String typeLabel = switch (p.getType()) {
                case FIVE_A_SIDE -> "Sân 5";
                case SEVEN_A_SIDE -> "Sân 7";
                case ELEVEN_A_SIDE -> "Sân 11";
            };

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pitchId", p.getPitchId().toString());
            item.put("name", p.getName());
            item.put("type", typeLabel);
            item.put("providerName", providerName);
            item.put("price", p.getPrice());
            item.put("environment", p.getEnvironment().name());
            content.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", pitchPage.getTotalElements());
        result.put("totalPages", pitchPage.getTotalPages());
        result.put("currentPage", page);
        return ResponseEntity.ok(result);
    }

    // ── REVIEWS ────────────────────────────────────────────────────────────────

    @GetMapping("/reviews/stats")
    public ResponseEntity<Map<String, Object>> getReviewStats() {
        long total = reviewRepository.count();
        Double avg = reviewRepository.findOverallAverageRating();

        // Count per star (1-5)
        Map<Integer, Long> starMap = new HashMap<>();
        for (Object[] row : reviewRepository.countByRating()) {
            starMap.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (int star = 5; star >= 1; star--) {
            long count = starMap.getOrDefault(star, 0L);
            double pct = total > 0 ? (count * 100.0 / total) : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stars", star);
            m.put("count", count);
            m.put("percentage", BigDecimal.valueOf(pct).setScale(1, RoundingMode.HALF_UP).doubleValue());
            distribution.add(m);
        }

        // Recent reviews
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Review r : reviewRepository.findTop10ByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reviewId", r.getReviewId().toString());
            m.put("userName", r.getUser() != null ? r.getUser().getName() : "—");
            m.put("rating", r.getRating());
            m.put("comment", r.getComment() != null ? r.getComment() : "");
            m.put("pitchName", r.getPitch() != null ? r.getPitch().getName() : "—");
            m.put("createdAt", r.getCreatedAt() != null
                    ? r.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "");
            recent.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("distribution", distribution);
        result.put("totalReviews", total);
        result.put("averageRating", avg != null
                ? BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP).doubleValue() : 0.0);
        result.put("recentReviews", recent);
        return ResponseEntity.ok(result);
    }
}