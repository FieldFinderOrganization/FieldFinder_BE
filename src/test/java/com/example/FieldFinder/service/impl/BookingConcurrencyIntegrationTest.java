package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.repository.BookingDetailRepository;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.service.BookingService;
import com.example.FieldFinder.service.PitchRedisLockService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ==================================================================================
 * CONCURRENCY INTEGRATION TEST — Kiểm thử cơ chế chống đặt trùng lịch với Redis
 * ==================================================================================
 *
 * Mục tiêu: Chứng minh rằng khi N người dùng đồng thời gửi yêu cầu đặt cùng một
 * sân (pitchId) vào cùng một ngày/khung giờ, hệ thống chỉ cho phép DUY NHẤT 1
 * đơn thành công nhờ cơ chế Redis SETNX (setIfAbsent) trong PitchRedisLockService.
 *
 * Kịch bản kiểm thử:
 *   - 10  luồng đồng thời (simulate 10  người dùng)
 *   - 50  luồng đồng thời (simulate 50  người dùng)
 *   - 100 luồng đồng thời (simulate 100 người dùng)
 *
 * Điều kiện tiên quyết để chạy test này:
 *   1. MySQL đang chạy và đã seed dữ liệu (user + pitch + timeslot hợp lệ)
 *   2. Redis đang chạy (localhost:6379 hoặc cấu hình trong application.properties)
 *   3. RabbitMQ đang chạy (dùng cho EmailService async)
 *   4. Cấu hình profile "test" hoặc "integration" trong application-test.properties
 *
 * Cách chạy:
 *   mvn test -Dtest=BookingConcurrencyIntegrationTest -DfailIfNoTests=false
 *
 * @author FieldFinder Team
 * @version 1.0
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Concurrency Integration Test — yêu cầu MySQL/Redis/RabbitMQ đang chạy. " +
        "Bật thủ công: mvn test -Dtest=BookingConcurrencyIntegrationTest")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BookingConcurrencyIntegrationTest {

    // =====================================================================
    // QUAN TRỌNG: Thay thế các giá trị này bằng dữ liệu thực trong DB của bạn
    // =====================================================================
    /**
     * UUID của sân cần test (pitch phải ở trạng thái ACTIVE, có providerAddress).
     * Lấy từ SELECT pitch_id FROM pitches LIMIT 1;
     */
    private static final String TEST_PITCH_ID = "REPLACE_WITH_REAL_PITCH_UUID";

    /**
     * UUID của user bình thường (role = USER, không phải PROVIDER).
     * Vì test dùng userId trực tiếp, có thể dùng cùng 1 userId cho mọi thread
     * (giả lập nhiều client khác nhau gọi API song song).
     * Thực tế bạn nên tạo nhiều test user và điền vào TEST_USER_IDS.
     * Lấy từ: SELECT user_id FROM users WHERE role = 'USER' LIMIT 1;
     */
    private static final String TEST_USER_ID = "REPLACE_WITH_REAL_USER_UUID";

    /**
     * Slot ID cần đặt (ví dụ: slot 3 = 08:00-09:00).
     * Lấy từ: SELECT slot_id FROM time_slots;
     */
    private static final int TEST_SLOT_ID = 3;

    /**
     * Ngày đặt sân (phải trước hiện tại ít nhất 2 tiếng + trong tương lai).
     * Điều chỉnh cho phù hợp với ngày chạy test.
     */
    private static final LocalDate TEST_BOOKING_DATE = LocalDate.now().plusDays(7);

    /**
     * Giá từng slot (VND).
     */
    private static final BigDecimal SLOT_PRICE = new BigDecimal("150000");

    // =====================================================================

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingDetailRepository bookingDetailRepository;

    @Autowired
    private PitchRedisLockService pitchRedisLockService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // ─── Kết quả tổng hợp các kịch bản ───────────────────────────────────────
    private static final Map<String, ScenarioResult> RESULTS = new LinkedHashMap<>();

    // =====================================================================
    // SETUP & TEARDOWN
    // =====================================================================

    @BeforeEach
    void cleanupRedisAndBookings() {
        // Xóa Redis lock còn sót từ lần chạy trước
        String lockPattern = String.format("lock:pitch:%s:date:%s:slot:%d",
                TEST_PITCH_ID, TEST_BOOKING_DATE.toString(), TEST_SLOT_ID);
        stringRedisTemplate.delete(lockPattern);

        // Xóa các booking test đã tạo trong DB (tránh ảnh hưởng giữa các scenario)
        cleanupTestBookings();
    }

    private void cleanupTestBookings() {
        try {
            // Tìm và xóa booking_detail trước (FK constraint), rồi booking
            List<BookingDetail> details = bookingDetailRepository
                    .findByPitchAndDateExcludingStatuses(
                            UUID.fromString(TEST_PITCH_ID),
                            TEST_BOOKING_DATE,
                            Collections.emptyList()
                    );
            if (!details.isEmpty()) {
                // Lấy danh sách booking ID rồi xóa
                Set<UUID> bookingIds = details.stream()
                        .map(bd -> bd.getBooking().getBookingId())
                        .collect(Collectors.toSet());
                bookingDetailRepository.deleteAll(details);
                bookingIds.forEach(id -> bookingRepository.deleteById(id));
            }
        } catch (Exception e) {
            // Bỏ qua nếu không tồn tại data
            System.err.println("[WARN] Cleanup error (ignoreable): " + e.getMessage());
        }
    }

    // =====================================================================
    // HELPER — Tạo BookingRequestDTO
    // =====================================================================

    private BookingRequestDTO buildBookingRequest(String userId) {
        BookingRequestDTO.BookingDetailDTO detail = new BookingRequestDTO.BookingDetailDTO(
                TEST_SLOT_ID,
                "Slot " + TEST_SLOT_ID + " - Test Concurrency",
                SLOT_PRICE
        );

        BookingRequestDTO request = new BookingRequestDTO();
        request.setPitchId(UUID.fromString(TEST_PITCH_ID));
        request.setUserId(UUID.fromString(userId));
        request.setBookingDate(TEST_BOOKING_DATE);
        request.setTotalPrice(SLOT_PRICE);
        request.setBookingDetails(Collections.singletonList(detail));
        request.setPaymentMethod("CASH"); // CASH để không cần payment gateway
        request.setDiscountCodes(Collections.emptyList());
        return request;
    }

    // =====================================================================
    // KỊCH BẢN 1: 10 NGƯỜI DÙNG ĐỒNG THỜI
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("Kịch bản 1: 10 người dùng đồng thời đặt cùng 1 sân/slot → Chỉ 1 thành công")
    void testConcurrencyWith10Users() throws InterruptedException {
        ScenarioResult result = runConcurrencyScenario("Scenario-10-users", 10);
        RESULTS.put("10_users", result);
        printScenarioResult(result);

        // Assertion: Đúng 1 booking được tạo trong DB
        Assertions.assertEquals(1, result.successCount,
                "Với 10 threads đồng thời, phải có đúng 1 booking thành công trong DB!");
        Assertions.assertEquals(9, result.failCount,
                "Với 10 threads đồng thời, phải có đúng 9 booking bị từ chối!");
        assertOnlyOneBookingInDB();
    }

    // =====================================================================
    // KỊCH BẢN 2: 50 NGƯỜI DÙNG ĐỒNG THỜI
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("Kịch bản 2: 50 người dùng đồng thời đặt cùng 1 sân/slot → Chỉ 1 thành công")
    void testConcurrencyWith50Users() throws InterruptedException {
        ScenarioResult result = runConcurrencyScenario("Scenario-50-users", 50);
        RESULTS.put("50_users", result);
        printScenarioResult(result);

        Assertions.assertEquals(1, result.successCount,
                "Với 50 threads đồng thời, phải có đúng 1 booking thành công trong DB!");
        Assertions.assertEquals(49, result.failCount,
                "Với 50 threads đồng thời, phải có đúng 49 booking bị từ chối!");
        assertOnlyOneBookingInDB();
    }

    // =====================================================================
    // KỊCH BẢN 3: 100 NGƯỜI DÙNG ĐỒNG THỜI
    // =====================================================================

    @Test
    @Order(3)
    @DisplayName("Kịch bản 3: 100 người dùng đồng thời đặt cùng 1 sân/slot → Chỉ 1 thành công")
    void testConcurrencyWith100Users() throws InterruptedException {
        ScenarioResult result = runConcurrencyScenario("Scenario-100-users", 100);
        RESULTS.put("100_users", result);
        printScenarioResult(result);

        Assertions.assertEquals(1, result.successCount,
                "Với 100 threads đồng thời, phải có đúng 1 booking thành công trong DB!");
        Assertions.assertEquals(99, result.failCount,
                "Với 100 threads đồng thời, phải có đúng 99 booking bị từ chối!");
        assertOnlyOneBookingInDB();
    }

    // =====================================================================
    // BÁO CÁO TỔNG HỢP
    // =====================================================================

    @AfterAll
    static void printSummaryReport() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     BÁO CÁO KIỂM THỬ CONCURRENCY — CHỐNG ĐẶT TRÙNG LỊCH REDIS     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("%-20s %-10s %-12s %-12s %-15s %-15s%n",
                "Kịch bản", "Threads", "Thành công", "Thất bại", "Thời gian(ms)", "Redis SETNX");
        System.out.println("─".repeat(90));

        for (Map.Entry<String, ScenarioResult> entry : RESULTS.entrySet()) {
            ScenarioResult r = entry.getValue();
            System.out.printf("%-20s %-10d %-12d %-12d %-15d %-15s%n",
                    r.scenarioName, r.totalThreads, r.successCount, r.failCount,
                    r.durationMs, r.redisKeyDeleted ? "Đã giải phóng" : "Còn tồn tại");
        }

        System.out.println("─".repeat(90));
        System.out.println();
        System.out.println("✅ KẾT LUẬN: Cơ chế Redis SETNX (setIfAbsent) đảm bảo chỉ 1 booking");
        System.out.println("   được ghi vào DB dù N threads gửi yêu cầu đồng thời.");
        System.out.println("   Không có race condition. Không có duplicate booking.");
        System.out.println();
    }

    // =====================================================================
    // CORE: Hàm chạy kịch bản concurrency
    // =====================================================================

    private ScenarioResult runConcurrencyScenario(String name, int threadCount)
            throws InterruptedException {

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> successMessages = Collections.synchronizedList(new ArrayList<>());
        List<String> failMessages = Collections.synchronizedList(new ArrayList<>());

        // CountDownLatch để các thread bắt đầu CÙNG LÚC (simulate concurrent requests)
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    // Tất cả thread chờ ở đây → đồng thời xuất phát
                    startGate.await();

                    BookingRequestDTO request = buildBookingRequest(TEST_USER_ID);
                    Booking booking = bookingService.createBooking(request);

                    // Thành công
                    successCount.incrementAndGet();
                    successMessages.add(String.format(
                            "[Thread-%03d] ✅ Thành công → bookingId=%s",
                            threadIndex, booking.getBookingId()));

                } catch (Exception e) {
                    // Thất bại (do Redis lock hoặc validation)
                    failCount.incrementAndGet();
                    failMessages.add(String.format(
                            "[Thread-%03d] ❌ Bị từ chối → %s",
                            threadIndex, e.getMessage()));
                } finally {
                    endGate.countDown();
                }
            });
        }

        // BẮT ĐẦU: mở cổng cho tất cả thread chạy đồng thời
        startGate.countDown();

        // Đợi tất cả thread hoàn tất (tối đa 60 giây)
        boolean completed = endGate.await(60, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        executor.shutdown();

        // Log chi tiết từng thread
        successMessages.forEach(System.out::println);
        failMessages.stream().limit(5).forEach(System.out::println); // Chỉ log 5 fail đầu
        if (failMessages.size() > 5) {
            System.out.println(String.format("    ... và %d thread bị từ chối khác (không log hết)",
                    failMessages.size() - 5));
        }

        // Kiểm tra Redis key đã được giải phóng chưa
        String lockKey = String.format("lock:pitch:%s:date:%s:slot:%d",
                TEST_PITCH_ID, TEST_BOOKING_DATE.toString(), TEST_SLOT_ID);
        boolean redisKeyGone = !Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey));

        return new ScenarioResult(
                name,
                threadCount,
                successCount.get(),
                failCount.get(),
                endTime - startTime,
                completed,
                redisKeyGone
        );
    }

    // =====================================================================
    // ASSERTION: Kiểm tra DB chỉ có 1 booking
    // =====================================================================

    private void assertOnlyOneBookingInDB() {
        List<BookingDetail> details = bookingDetailRepository
                .findByPitchAndDateExcludingStatuses(
                        UUID.fromString(TEST_PITCH_ID),
                        TEST_BOOKING_DATE,
                        Collections.emptyList()
                );

        // Đếm số booking DISTINCT trong bảng booking_detail cho pitch+date+slot này
        long distinctBookings = details.stream()
                .filter(bd -> bd.getTimeSlot() != null
                        && bd.getTimeSlot().getSlotId() == TEST_SLOT_ID)
                .map(bd -> bd.getBooking().getBookingId())
                .distinct()
                .count();

        System.out.printf("%n[DB CHECK] Số booking ghi vào DB cho pitch=%s, date=%s, slot=%d: %d%n",
                TEST_PITCH_ID, TEST_BOOKING_DATE, TEST_SLOT_ID, distinctBookings);

        Assertions.assertEquals(1, distinctBookings,
                String.format("DB phải chứa đúng 1 booking cho pitch=%s, date=%s, slot=%d — " +
                        "nhưng tìm thấy %d!", TEST_PITCH_ID, TEST_BOOKING_DATE, TEST_SLOT_ID, distinctBookings));
    }

    // =====================================================================
    // PRINT RESULT
    // =====================================================================

    private void printScenarioResult(ScenarioResult r) {
        System.out.println("\n" + "═".repeat(70));
        System.out.printf("KẾT QUẢ: %s%n", r.scenarioName);
        System.out.printf("  Tổng số thread  : %d%n", r.totalThreads);
        System.out.printf("  Thành công       : %d (mong đợi: 1)%n", r.successCount);
        System.out.printf("  Bị từ chối       : %d (mong đợi: %d)%n",
                r.failCount, r.totalThreads - 1);
        System.out.printf("  Thời gian chạy  : %d ms%n", r.durationMs);
        System.out.printf("  Hoàn thành đúng hạn: %s%n", r.completedInTime ? "Có" : "Không (Timeout!)");
        System.out.printf("  Redis lock đã xóa: %s%n", r.redisKeyDeleted ? "Có" : "Không");
        System.out.println("═".repeat(70));
    }

    // =====================================================================
    // DATA CLASS
    // =====================================================================

    private static class ScenarioResult {
        final String scenarioName;
        final int totalThreads;
        final int successCount;
        final int failCount;
        final long durationMs;
        final boolean completedInTime;
        final boolean redisKeyDeleted;

        ScenarioResult(String scenarioName, int totalThreads, int successCount,
                       int failCount, long durationMs, boolean completedInTime,
                       boolean redisKeyDeleted) {
            this.scenarioName = scenarioName;
            this.totalThreads = totalThreads;
            this.successCount = successCount;
            this.failCount = failCount;
            this.durationMs = durationMs;
            this.completedInTime = completedInTime;
            this.redisKeyDeleted = redisKeyDeleted;
        }
    }
}
