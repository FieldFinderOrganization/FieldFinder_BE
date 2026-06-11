package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.CancelActor;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.TimeSlot;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.*;
import com.example.FieldFinder.service.DiscountUsageService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplProviderCancelTest {

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

    BookingServiceImpl service;

    private UUID bookingId;
    private UUID providerUserId;
    private UUID providerId;
    private User customer;
    private Provider provider;

    @BeforeEach
    void setUp() {
        service = new BookingServiceImpl(
                bookingRepository, bookingDetailRepository, pitchRepository,
                userRepository, restTemplate, rabbitTemplate,
                pitchRedisLockService, entityManager, paymentRepository,
                providerRepository, emailService, refundService,
                discountRepository, discountUsageService, userDiscountRepository);

        bookingId = UUID.randomUUID();
        providerUserId = UUID.randomUUID();
        providerId = UUID.randomUUID();

        customer = new User();
        customer.setUserId(UUID.randomUUID());

        User providerUser = new User();
        providerUser.setUserId(providerUserId);
        provider = Provider.builder().providerId(providerId).user(providerUser).build();

        // unlockSlotsAfterCommit cần synchronization đang active
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Booking với 1 slot bắt đầu sau {@code minutesUntilStart} phút, tạo {@code createdMinutesBeforeStart} phút trước slot. */
    private Booking booking(long minutesUntilStart, long createdMinutesBeforeStart,
                            BookingStatus status, PaymentStatus paymentStatus) {
        LocalDateTime slotStart = LocalDateTime.now().plusMinutes(minutesUntilStart);

        Pitch pitch = Pitch.builder()
                .pitchId(UUID.randomUUID())
                .providerAddress(ProviderAddress.builder().provider(provider).build())
                .build();
        TimeSlot slot = TimeSlot.builder()
                .slotId(3)
                .startTime(slotStart.toLocalTime())
                .endTime(slotStart.toLocalTime().plusHours(1))
                .build();

        Booking b = Booking.builder()
                .bookingId(bookingId)
                .user(customer)
                .bookingDate(slotStart.toLocalDate())
                .createdAt(slotStart.minusMinutes(createdMinutesBeforeStart))
                .status(status)
                .paymentStatus(paymentStatus)
                .totalPrice(new BigDecimal("200000"))
                .bookingDetails(new ArrayList<>())
                .build();

        BookingDetail detail = BookingDetail.builder()
                .booking(b).pitch(pitch).timeSlot(slot).build();
        b.getBookingDetails().add(detail);
        return b;
    }

    private Payment payment(PaymentMethod method, PaymentStatus status) {
        return Payment.builder().paymentMethod(method).paymentStatus(status).build();
    }

    private void stubBooking(Booking b) {
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(b));
        when(providerRepository.findByUser_UserId(providerUserId)).thenReturn(Optional.of(provider));
    }

    // ---------- reject cases ----------

    @Test
    void throws_whenReasonBlank() {
        assertThatThrownBy(() -> service.cancelBookingByProvider(bookingId, providerUserId, "  "))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("lý do");
        verifyNoInteractions(bookingRepository, refundService);
    }

    @Test
    void throws_whenAlreadyCanceled() {
        Booking b = booking(120, 300, BookingStatus.CANCELED, PaymentStatus.CANCELED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.cancelBookingByProvider(bookingId, providerUserId, "Sân hỏng"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("đã bị hủy");
        verifyNoInteractions(refundService);
    }

    @Test
    void throws_whenNotOwner() {
        Booking b = booking(120, 300, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        // sân thuộc provider khác
        User otherUser = new User();
        otherUser.setUserId(UUID.randomUUID());
        Provider other = Provider.builder().providerId(UUID.randomUUID()).user(otherUser).build();
        b.getBookingDetails().getFirst().getPitch().getProviderAddress().setProvider(other);
        stubBooking(b);

        assertThatThrownBy(() -> service.cancelBookingByProvider(bookingId, providerUserId, "Sân hỏng"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("không có quyền");
        verifyNoInteractions(refundService);
    }

    @Test
    void throws_whenSlotAlreadyStarted() {
        Booking b = booking(-30, 300, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        stubBooking(b);

        assertThatThrownBy(() -> service.cancelBookingByProvider(bookingId, providerUserId, "Sân hỏng"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("diễn ra");
        verifyNoInteractions(refundService);
    }

    // ---------- refund branches ----------

    @Test
    void pendingBankUnpaid_cancels_noCredit_setsAudit() {
        Booking b = booking(120, 300, BookingStatus.PENDING, PaymentStatus.PENDING);
        stubBooking(b);
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                .thenReturn(Optional.of(payment(PaymentMethod.BANK, PaymentStatus.PENDING)));

        service.cancelBookingByProvider(bookingId, providerUserId, "Sân bảo trì");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELED);
        assertThat(b.getCancelledBy()).isEqualTo(CancelActor.PROVIDER);
        assertThat(b.getCancelReason()).isEqualTo("Sân bảo trì");
        verify(emailService).sendBookingCancellation(b);
        verify(discountUsageService).revertForBooking(bookingId);
        verifyNoInteractions(refundService);
    }

    @Test
    void paidBank_cancelEarly_credits100Percent_restrictedToProvider() {
        Booking b = booking(180, 600, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        stubBooking(b);
        Payment p = payment(PaymentMethod.BANK, PaymentStatus.PAID);
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                .thenReturn(Optional.of(p));
        when(refundService.issueRefundCredit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundRequest());

        service.cancelBookingByProvider(bookingId, providerUserId, "Sân hỏng");

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(refundService).issueRefundCredit(
                eq(customer), eq(RefundSourceType.BOOKING), eq(bookingId.toString()),
                amount.capture(), eq("Sân hỏng"), eq(providerId));
        assertThat(amount.getValue()).isEqualByComparingTo("200000"); // 100%

        assertThat(p.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(b.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void paidBank_lateCancel_oldBooking_credits110Percent() {
        // hủy còn 30p, đơn tạo 600p trước slot → 110%
        Booking b = booking(30, 600, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        stubBooking(b);
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                .thenReturn(Optional.of(payment(PaymentMethod.BANK, PaymentStatus.PAID)));
        when(refundService.issueRefundCredit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundRequest());

        service.cancelBookingByProvider(bookingId, providerUserId, "Sân hỏng");

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(refundService).issueRefundCredit(any(), any(), any(),
                amount.capture(), any(), eq(providerId));
        assertThat(amount.getValue()).isEqualByComparingTo("220000"); // 110%
    }

    @Test
    void paidBank_lateCancel_freshBooking_credits100Percent_noFarm() {
        // hủy còn 30p NHƯNG đơn cũng chỉ tạo 40p trước slot → 100%, chặn farm
        Booking b = booking(30, 40, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        stubBooking(b);
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                .thenReturn(Optional.of(payment(PaymentMethod.BANK, PaymentStatus.PAID)));
        when(refundService.issueRefundCredit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundRequest());

        service.cancelBookingByProvider(bookingId, providerUserId, "Sân hỏng");

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(refundService).issueRefundCredit(any(), any(), any(),
                amount.capture(), any(), eq(providerId));
        assertThat(amount.getValue()).isEqualByComparingTo("200000"); // 100%
    }

    @Test
    void cash_cancels_noCredit_paymentStatusCanceled() {
        // CASH được set CONFIRMED+PAID "lạc quan" từ lúc tạo — khách chưa trả tiền thật
        Booking b = booking(120, 300, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        stubBooking(b);
        Payment p = payment(PaymentMethod.CASH, PaymentStatus.PAID);
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(bookingId))
                .thenReturn(Optional.of(p));

        service.cancelBookingByProvider(bookingId, providerUserId, "Thời tiết xấu");

        verifyNoInteractions(refundService);
        assertThat(p.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(b.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELED);
        assertThat(b.getCancelledBy()).isEqualTo(CancelActor.PROVIDER);
    }
}
