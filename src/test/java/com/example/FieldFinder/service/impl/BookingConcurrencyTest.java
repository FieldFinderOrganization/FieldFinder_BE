package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.dto.req.BookingRequestDTO;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.TimeSlot;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.DiscountUsageService;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.PitchRedisLockService;
import com.example.FieldFinder.service.RefundService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingConcurrencyTest {

    @Mock BookingRepository bookingRepository;
    @Mock BookingDetailRepository bookingDetailRepository;
    @Mock PitchRepository pitchRepository;
    @Mock UserRepository userRepository;
    @Mock RestTemplate restTemplate;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock PitchRedisLockService pitchRedisLockService;
    @Mock EntityManager entityManager;
    @Mock PaymentRepository paymentRepository;
    @Mock ProviderRepository providerRepository;
    @Mock EmailService emailService;
    @Mock RefundService refundService;
    @Mock DiscountRepository discountRepository;
    @Mock DiscountUsageService discountUsageService;
    @Mock UserDiscountRepository userDiscountRepository;
    @Mock NotificationService notificationService;
    @Mock TimeSlotRepository timeSlotRepository;

    @InjectMocks BookingServiceImpl service;

    private UUID userId;
    private UUID pitchId;
    private final LocalDate date = LocalDate.now().plusDays(3);

    /** Tập slot đang bị giữ — đóng vai Redis. Toàn-bộ-hoặc-không, atomic. */
    private final Set<String> heldSlots = ConcurrentHashMap.newKeySet();

    private static String key(UUID pitchId, LocalDate d, Integer slot) {
        return pitchId + "|" + d + "|" + slot;
    }

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        pitchId = UUID.randomUUID();

        User user = new User();
        user.setUserId(userId);
        Pitch pitch = new Pitch();
        pitch.setPitchId(pitchId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(pitchRepository.findById(pitchId)).thenReturn(Optional.of(pitch));
        when(entityManager.getReference(eq(TimeSlot.class), any())).thenReturn(new TimeSlot());
        // createBooking đọc slot qua timeSlotRepository.findAllById → trả slot giờ xa (qua mốc 120').
        when(timeSlotRepository.findAllById(anyList())).thenAnswer(inv -> {
            List<Integer> ids = inv.getArgument(0);
            return ids.stream().map(id -> {
                TimeSlot ts = new TimeSlot();
                ts.setSlotId(id);
                ts.setStartTime(java.time.LocalTime.of(18, 0));
                return ts;
            }).toList();
        });
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            if (b.getBookingId() == null) b.setBookingId(UUID.randomUUID());
            return b;
        });

        // Giả lập Redis SETNX: chiếm tất cả slot trong 1 lần atomic, ai tới trước thắng.
        when(pitchRedisLockService.lockSlots(any(), any(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    UUID pid = inv.getArgument(0);
                    LocalDate d = inv.getArgument(1);
                    List<Integer> slots = inv.getArgument(2);
                    synchronized (heldSlots) {
                        for (Integer s : slots) {
                            if (heldSlots.contains(key(pid, d, s))) return false;
                        }
                        for (Integer s : slots) heldSlots.add(key(pid, d, s));
                        return true;
                    }
                });
        doAnswer(inv -> {
            UUID pid = inv.getArgument(0);
            LocalDate d = inv.getArgument(1);
            Integer s = inv.getArgument(2);
            heldSlots.remove(key(pid, d, s));
            return null;
        }).when(pitchRedisLockService).unlockSlot(any(), any(), anyInt(), anyString());
    }

    private BookingRequestDTO requestForSlot(int slot) {
        BookingRequestDTO.BookingDetailDTO detail = new BookingRequestDTO.BookingDetailDTO(
                slot, "Slot " + slot, new BigDecimal("200000"));
        BookingRequestDTO req = new BookingRequestDTO();
        req.setUserId(userId);
        req.setPitchId(pitchId);
        req.setBookingDate(date);
        req.setBookingDetails(List.of(detail));
        // paymentMethod null -> nhánh PENDING, không đụng payment/notification
        return req;
    }

    @Test
    void only_one_booking_wins_when_many_threads_grab_same_slot() throws InterruptedException {
        int threads = 20;
        int contestedSlot = 5;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();                          // chờ chung 1 vạch
                    service.createBooking(requestForSlot(contestedSlot));
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();                                  // thả tất cả cùng lúc
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.printf(
                "[DAT SAN] %d thread tranh slot #%d cung luc -> %d thang / %d truot | slot giu=%d%n",
                threads, contestedSlot, success.get(), failure.get(), heldSlots.size());

        assertThat(finished).as("tất cả thread phải kết thúc").isTrue();
        assertThat(success.get()).as("chỉ 1 người đặt được slot").isEqualTo(1);
        assertThat(failure.get()).as("N-1 người còn lại bị từ chối").isEqualTo(threads - 1);
        assertThat(heldSlots).as("đúng 1 slot bị giữ, không đặt trùng")
                .containsExactly(key(pitchId, date, contestedSlot));
    }

    @Test
    void different_slots_all_succeed_in_parallel() throws InterruptedException {
        int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int slot = i;                                   // mỗi thread 1 slot khác nhau
            pool.submit(() -> {
                try {
                    start.await();
                    service.createBooking(requestForSlot(slot));
                    success.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.printf(
                "[DAT SAN] %d thread, %d slot khac nhau -> %d thang (khong tranh chap) | slot giu=%d%n",
                threads, threads, success.get(), heldSlots.size());

        assertThat(finished).isTrue();
        // Slot không đụng nhau -> không tranh chấp -> tất cả thắng.
        assertThat(success.get()).isEqualTo(threads);
        assertThat(heldSlots).hasSize(threads);
    }
}
