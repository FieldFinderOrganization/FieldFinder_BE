package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.entity.TimeSlot;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.repository.ProviderRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.DiscountUsageService;
import com.example.FieldFinder.service.EmailService;
import com.example.FieldFinder.service.NotificationService;
import com.example.FieldFinder.service.RefundService;
import com.example.FieldFinder.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 0+2: chủ sân hủy → phạt = refund − giá gốc (KHÔNG full refund) → TRỪ VÍ (CANCEL_PENALTY).
 * Hủy thường (100%) → phạt 0 → không trừ ví.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceProviderCancelTest {

    @Mock BookingRepository bookingRepository;
    @Mock ProviderRepository providerRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock BankAccountService bankAccountService;
    @Mock RefundService refundService;
    @Mock WalletService walletService;
    @Mock DiscountUsageService discountUsageService;
    @Mock EmailService emailService;
    @Mock NotificationService notificationService;

    @InjectMocks BookingServiceImpl service;

    private UUID providerUserId;
    private Provider provider;

    @BeforeEach
    void setUp() {
        providerUserId = UUID.randomUUID();
        User providerUser = new User();
        providerUser.setUserId(providerUserId);
        provider = new Provider();
        provider.setProviderId(UUID.randomUUID());
        provider.setUser(providerUser);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // slotStart truyền cả ngày+giờ để earliestStart không tràn ngày khi now+offset qua nửa đêm.
    private Booking bankBooking(LocalDateTime slotStart, LocalDateTime createdAt) {
        User customer = new User();
        customer.setUserId(UUID.randomUUID());
        ProviderAddress addr = new ProviderAddress();
        addr.setProvider(provider);
        Pitch pitch = new Pitch();
        pitch.setProviderAddress(addr);
        TimeSlot ts = new TimeSlot();
        ts.setStartTime(slotStart.toLocalTime());
        BookingDetail bd = new BookingDetail();
        bd.setPitch(pitch);
        bd.setTimeSlot(ts);
        List<BookingDetail> details = new ArrayList<>();
        details.add(bd);
        Booking booking = Booking.builder()
                .bookingId(UUID.randomUUID())
                .user(customer)
                .bookingDate(slotStart.toLocalDate())
                .status(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PAID)
                .totalPrice(new BigDecimal("200000"))
                .createdAt(createdAt)
                .bookingDetails(details)
                .build();
        bd.setBooking(booking);
        return booking;
    }

    private void commonStubs(Booking b) {
        when(bookingRepository.findById(b.getBookingId())).thenReturn(Optional.of(b));
        when(providerRepository.findByUser_UserId(providerUserId)).thenReturn(Optional.of(provider));
        when(paymentRepository.findAllByBookingIds(anyList())).thenReturn(List.of());
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.BANK).build()));
        when(bankAccountService.getDefault(b.getUser().getUserId()))
                .thenReturn(Optional.of(BankAccount.builder()
                        .bankBin("970436").accountNumber("1").accountName("CUST").build()));
    }

    @Test
    void lateCancel_debitsPenaltyOnly_not110Percent() {
        Booking b = bankBooking(LocalDateTime.now().plusMinutes(30),
                LocalDateTime.now().minusHours(5)); // late → 110% → phạt 10% = 20.000
        commonStubs(b);

        service.cancelBookingByProvider(b.getBookingId(), providerUserId, "sân ngập");

        verify(walletService).debit(eq(provider), eq(WalletTxnType.CANCEL_PENALTY),
                eq(new BigDecimal("20000")), eq("BOOKING"), eq(b.getBookingId().toString()), anyString());
    }

    @Test
    void normalCancel_noPenalty_noDebit() {
        Booking b = bankBooking(LocalDateTime.now().plusMinutes(120),
                LocalDateTime.now().minusHours(5)); // ≥60p → 100% → phạt 0
        commonStubs(b);

        service.cancelBookingByProvider(b.getBookingId(), providerUserId, "đổi lịch");

        verify(walletService, never()).debit(any(), any(), any(), any(), any(), any());
    }
}
