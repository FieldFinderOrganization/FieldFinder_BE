package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.TimeSlot;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.PitchRedisLockService;
import com.example.FieldFinder.service.RefundService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

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
    @Mock UserDiscountRepository userDiscountRepository;

    BookingServiceImpl service;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new BookingServiceImpl(
                bookingRepository, bookingDetailRepository, pitchRepository,
                userRepository, restTemplate, rabbitTemplate,
                pitchRedisLockService, entityManager, paymentRepository,
                providerRepository, emailService, refundService, discountRepository, userDiscountRepository);

        userId = UUID.randomUUID();
        user = new User();
        user.setUserId(userId);

        // cancelBookingByUser gọi registerSynchronization → cần active synchronization
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Booking buildBooking(BookingStatus status, PaymentStatus paid,
                                 LocalTime slotStart, LocalDate date) {
        Pitch pitch = new Pitch();
        TimeSlot ts = new TimeSlot();
        ts.setSlotId(1);
        ts.setStartTime(slotStart);

        BookingDetail bd = new BookingDetail();
        bd.setPitch(pitch);
        bd.setTimeSlot(ts);
        bd.setName(slotStart + " - " + slotStart.plusHours(1));

        List<BookingDetail> details = new ArrayList<>();
        details.add(bd);

        Booking booking = Booking.builder()
                .bookingId(UUID.randomUUID())
                .user(user)
                .bookingDate(date)
                .status(status)
                .paymentStatus(paid)
                .totalPrice(new BigDecimal("200000"))
                .bookingDetails(details)
                .build();
        bd.setBooking(booking);
        return booking;
    }

    @Test
    void cancel_throws_whenBookingNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelBookingByUser(id, userId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Booking not found");
    }

    @Test
    void cancel_throws_whenOtherUser() {
        Booking b = buildBooking(BookingStatus.PENDING, PaymentStatus.PENDING,
                LocalTime.of(18, 0), LocalDate.now().plusDays(1));
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() ->
                service.cancelBookingByUser(b.getBookingId(), UUID.randomUUID(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("không có quyền");
    }

    @Test
    void cancel_throws_whenStatusNotEligible() {
        Booking b = buildBooking(BookingStatus.CANCELED, PaymentStatus.PENDING,
                LocalTime.of(18, 0), LocalDate.now().plusDays(1));
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() ->
                service.cancelBookingByUser(b.getBookingId(), userId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("không cho phép hủy");
    }

    @Test
    void cancel_paidConfirmed_lessThanWindow_throws_noRefund() {
        // slot bắt đầu sau 30 phút → không đủ 60 phút trước
        LocalTime now = LocalTime.now();
        Booking b = buildBooking(BookingStatus.CONFIRMED, PaymentStatus.PAID,
                now.plusMinutes(30), LocalDate.now());
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() ->
                service.cancelBookingByUser(b.getBookingId(), userId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ít nhất 60 phút");

        verifyNoInteractions(refundService);
    }

    @Test
    void cancel_pending_setsCanceled_noRefund() {
        Booking b = buildBooking(BookingStatus.PENDING, PaymentStatus.PENDING,
                LocalTime.of(20, 0), LocalDate.now().plusDays(1));
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));
        when(paymentRepository.findAllByBookingIds(anyList()))
                .thenReturn(List.of());

        service.cancelBookingByUser(b.getBookingId(), userId, "schedule conflict");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELED);
        verify(bookingRepository, atLeastOnce()).save(b);
        verifyNoInteractions(refundService);
    }

    @Test
    void cancel_paidConfirmed_bank_withinWindow_issuesRefund_marksRefunded() {
        // slot bắt đầu sau 120 phút → còn cửa sổ (>60p)
        LocalTime start = LocalTime.now().plusMinutes(120);
        Booking b = buildBooking(BookingStatus.CONFIRMED, PaymentStatus.PAID,
                start, LocalDate.now());
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));
        when(paymentRepository.findAllByBookingIds(anyList()))
                .thenReturn(List.of());

        Payment payment = Payment.builder()
                .paymentId(7L)
                .paymentMethod(PaymentMethod.BANK)
                .paymentStatus(PaymentStatus.PAID)
                .build();
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(payment));
        when(refundService.issueRefundCredit(any(), any(), any(), any(), any()))
                .thenReturn(new RefundRequest());

        service.cancelBookingByUser(b.getBookingId(), userId, "weather");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELED);
        assertThat(b.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(refundService).issueRefundCredit(
                eq(user), eq(RefundSourceType.BOOKING),
                eq(b.getBookingId().toString()),
                amount.capture(), reason.capture());
        assertThat(amount.getValue()).isEqualByComparingTo("200000");
        assertThat(reason.getValue()).isEqualTo("weather");

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getProcessedAt()).isNotNull();
        verify(paymentRepository).save(payment);
    }

    @Test
    void cancel_paidConfirmed_cash_withinWindow_cancelsButNoRefund() {
        // CASH: hủy là hủy, không tạo mã hoàn tiền, không đánh dấu REFUNDED
        LocalTime start = LocalTime.now().plusMinutes(120);
        Booking b = buildBooking(BookingStatus.CONFIRMED, PaymentStatus.PAID,
                start, LocalDate.now());
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));
        when(paymentRepository.findAllByBookingIds(anyList()))
                .thenReturn(List.of());

        Payment payment = Payment.builder()
                .paymentId(7L)
                .paymentMethod(PaymentMethod.CASH)
                .paymentStatus(PaymentStatus.PAID)
                .build();
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(payment));

        service.cancelBookingByUser(b.getBookingId(), userId, "đổi lịch");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELED);
        // payment + booking giữ nguyên PAID, không refund
        assertThat(b.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        verify(refundService, never()).issueRefundCredit(any(), any(), any(), any(), any());
    }
}