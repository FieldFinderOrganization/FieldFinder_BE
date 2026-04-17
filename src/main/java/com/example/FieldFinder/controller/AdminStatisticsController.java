package com.example.FieldFinder.controller;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.OrderStatus;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/admin/statistics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatisticsController {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PitchRepository pitchRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;

    public AdminStatisticsController(BookingRepository bookingRepository,
                                     UserRepository userRepository,
                                     PitchRepository pitchRepository,
                                     OrderRepository orderRepository,
                                     ReviewRepository reviewRepository,
                                     ProductRepository productRepository,
                                     OrderItemRepository orderItemRepository) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.pitchRepository = pitchRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        LocalDate today = LocalDate.now();

        long totalUsers = userRepository.count();
        long totalPitches = pitchRepository.count();
        long bookingsTodayCount = bookingRepository.countByBookingDate(today);
        long pendingOrdersCount = orderRepository.countByStatus(OrderStatus.PENDING);
        BigDecimal bookingRevenue = bookingRepository.sumTotalPriceByPaymentStatus(PaymentStatus.PAID);
        if (bookingRevenue == null) bookingRevenue = BigDecimal.ZERO;

        Double productRevenueRaw = orderItemRepository.sumRevenueByOrderStatuses(
                List.of(OrderStatus.PAID, OrderStatus.CONFIRMED, OrderStatus.DELIVERED));
        BigDecimal productRevenue = productRevenueRaw != null
                ? BigDecimal.valueOf(productRevenueRaw)
                : BigDecimal.ZERO;

        BigDecimal totalRevenue = bookingRevenue.add(productRevenue);
        Double avgRating = reviewRepository.findOverallAverageRating();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRevenue", totalRevenue);
        response.put("bookingRevenue", bookingRevenue);
        response.put("productRevenue", productRevenue);
        response.put("revenueChangePercent", 0.0);
        response.put("totalUsers", totalUsers);
        response.put("usersChangePercent", 0.0);
        response.put("totalPitches", totalPitches);
        response.put("pitchesChangePercent", 0.0);
        response.put("bookingsTodayCount", bookingsTodayCount);
        response.put("bookingsTodayChangePercent", 0.0);
        response.put("pendingOrdersCount", pendingOrdersCount);
        response.put("averageRating", avgRating != null ? BigDecimal.valueOf(avgRating).setScale(1, RoundingMode.HALF_UP) : 0.0);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/revenue")
    public ResponseEntity<List<Map<String, Object>>> getRevenue(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        // Booking revenue by date
        Map<String, BigDecimal> revenueByDate = new LinkedHashMap<>();
        for (Object[] row : bookingRepository.findRevenueByDateRange(start, end)) {
            String date = row[0].toString();
            BigDecimal amount = new BigDecimal(row[1].toString());
            revenueByDate.merge(date, amount, BigDecimal::add);
        }

        // Product revenue by date (sum of order_items for paid orders)
        List<OrderStatus> paidStatuses = List.of(OrderStatus.PAID, OrderStatus.CONFIRMED, OrderStatus.DELIVERED);
        for (Object[] row : orderItemRepository.findProductRevenueByDateRange(paidStatuses, start, end)) {
            String date = row[0].toString();
            BigDecimal amount = new BigDecimal(row[1].toString());
            revenueByDate.merge(date, amount, BigDecimal::add);
        }

        // Sort by date and build response
        List<Map<String, Object>> result = revenueByDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("date", e.getKey());
                    point.put("revenue", e.getValue());
                    return point;
                })
                .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/bookings-by-day")
    public ResponseEntity<List<Map<String, Object>>> getBookingsByDay() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(6);

        List<Object[]> raw = bookingRepository.countBookingsByDayOfWeek(start, end);
        Map<Integer, Long> countByDow = new HashMap<>();
        for (Object[] row : raw) {
            int dow = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            countByDow.put(dow, count);
        }

        // DAYOFWEEK: 1=Sunday, 2=Monday, ..., 7=Saturday
        String[] labels = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
        List<Map<String, Object>> result = new ArrayList<>();
        // Build from today going back 7 days in correct weekday order
        for (int i = 6; i >= 0; i--) {
            LocalDate day = end.minusDays(i);
            int dow = day.getDayOfWeek().getValue() % 7 + 1; // convert to DAYOFWEEK format
            String label = labels[dow - 1];
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("dayLabel", label);
            point.put("count", countByDow.getOrDefault(dow, 0L));
            result.add(point);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/pitches-by-type")
    public ResponseEntity<List<Map<String, Object>>> getPitchesByType() {
        List<Object[]> raw = pitchRepository.countByType();
        long total = pitchRepository.count();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : raw) {
            Pitch.PitchType type = (Pitch.PitchType) row[0];
            long count = ((Number) row[1]).longValue();
            double percentage = total > 0 ? (double) count / total * 100 : 0;

            String label = switch (type) {
                case FIVE_A_SIDE -> "Sân 5";
                case SEVEN_A_SIDE -> "Sân 7";
                case ELEVEN_A_SIDE -> "Sân 11";
            };

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("type", label);
            point.put("count", count);
            point.put("percentage", BigDecimal.valueOf(percentage).setScale(1, RoundingMode.HALF_UP));
            result.add(point);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/recent-bookings")
    public ResponseEntity<List<Map<String, Object>>> getRecentBookings() {
        List<Booking> bookings = bookingRepository.findTopRecentBookings(PageRequest.of(0, 5));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Booking b : bookings) {
            String userName = b.getUser() != null ? b.getUser().getName() : "Khách";
            String initials = buildInitials(userName);
            String pitchName = b.getBookingDetails() != null && !b.getBookingDetails().isEmpty()
                    && b.getBookingDetails().get(0).getPitch() != null
                    ? b.getBookingDetails().get(0).getPitch().getName()
                    : "Sân";

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bookingId", b.getBookingId().toString());
            item.put("userName", userName);
            item.put("userInitials", initials);
            item.put("description", "Đặt " + pitchName);
            item.put("timeAgo", timeAgo(b.getCreatedAt()));
            item.put("status", b.getStatus().name());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/products")
    public ResponseEntity<Map<String, Object>> getProductStatistics() {
        long totalProducts = productRepository.countAllProducts();
        Long totalSold = productRepository.sumTotalSold();

        // Top 5 sản phẩm bán chạy theo doanh thu từ đơn hàng
        List<Object[]> topRaw = orderItemRepository.findTopSellingProductsWithRevenue(PageRequest.of(0, 5));
        List<Map<String, Object>> topProducts = new ArrayList<>();
        for (Object[] row : topRaw) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row[0]);
            item.put("name", row[1]);
            item.put("imageUrl", row[2]);
            item.put("totalSold", ((Number) row[3]).longValue());
            item.put("totalRevenue", row[4]);
            topProducts.add(item);
        }

        // Phân bố theo danh mục
        List<Object[]> catRaw = productRepository.countByCategory();
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Object[] row : catRaw) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", row[0] != null ? row[0] : "Chưa phân loại");
            item.put("count", ((Number) row[1]).longValue());
            byCategory.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalProducts", totalProducts);
        response.put("totalSold", totalSold != null ? totalSold : 0L);
        response.put("topProducts", topProducts);
        response.put("byCategory", byCategory);

        return ResponseEntity.ok(response);
    }

    private String buildInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0)));
        }
        return sb.length() > 2 ? sb.substring(sb.length() - 2) : sb.toString();
    }

    private String timeAgo(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        Duration duration = Duration.between(dateTime, LocalDateTime.now());
        long minutes = duration.toMinutes();
        if (minutes < 1) return "Vừa xong";
        if (minutes < 60) return minutes + " phút trước";
        long hours = duration.toHours();
        if (hours < 24) return hours + " giờ trước";
        long days = duration.toDays();
        return days + " ngày trước";
    }
}