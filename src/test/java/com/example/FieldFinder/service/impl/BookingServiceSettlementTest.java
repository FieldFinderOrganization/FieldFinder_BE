package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.BookingDetail;
import com.example.FieldFinder.entity.Payment;
import com.example.FieldFinder.entity.Pitch;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderAddress;
import com.example.FieldFinder.entity.ProviderDebt;
import com.example.FieldFinder.entity.TimeSlot;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.repository.ProviderDebtRepository;
import com.example.FieldFinder.service.BankAccountService;
import com.example.FieldFinder.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceSettlementTest {

    @Mock BookingRepository bookingRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock RefundService refundService;
    @Mock ProviderDebtRepository providerDebtRepository;
    @Mock BankAccountService bankAccountService;

    @InjectMocks BookingServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "payoutCommissionRate", new BigDecimal("0.05"));
        ReflectionTestUtils.setField(service, "providerDebtSettleDays", 7L);
    }

    private Booking bankBooking(LocalTime slotEnd, LocalDate date, User host) {
        Provider provider = new Provider();
        provider.setUser(host);
        ProviderAddress addr = new ProviderAddress();
        addr.setProvider(provider);
        Pitch pitch = new Pitch();
        pitch.setProviderAddress(addr);
        TimeSlot ts = new TimeSlot();
        ts.setEndTime(slotEnd);
        BookingDetail bd = new BookingDetail();
        bd.setPitch(pitch);
        bd.setTimeSlot(ts);
        List<BookingDetail> details = new ArrayList<>();
        details.add(bd);
        Booking booking = Booking.builder()
                .bookingId(UUID.randomUUID())
                .bookingDate(date)
                .status(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PAID)
                .totalPrice(new BigDecimal("200000"))
                .bookingDetails(details)
                .build();
        bd.setBooking(booking);
        return booking;
    }

    private User host() {
        User u = new User();
        u.setUserId(UUID.randomUUID());
        return u;
    }

    @Test
    void endedBankBooking_hasBank_issuesPayoutMinusCommission() {
        User host = host();
        Booking b = bankBooking(LocalTime.of(8, 0), LocalDate.now().minusDays(1), host); // hôm qua → đã đá xong

        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.BANK).build()));
        when(refundService.findBySource(RefundSourceType.BOOKING_PAYOUT, b.getBookingId().toString()))
                .thenReturn(Optional.empty());
        when(providerDebtRepository.existsBySourceBookingId(anyString())).thenReturn(false);
        BankAccount bank = BankAccount.builder()
                .bankBin("970436").accountNumber("1").accountName("HOST").build();
        when(bankAccountService.getDefault(host.getUserId())).thenReturn(Optional.of(bank));

        service.settleBookingsToProvider();

        // 200000 * (1 - 0.05) = 190000
        verify(refundService).issueCashRefund(eq(host), eq(RefundSourceType.BOOKING_PAYOUT),
                eq(b.getBookingId().toString()), eq(new BigDecimal("190000")), anyString(), eq(bank));
        verify(providerDebtRepository, never()).save(any());
    }

    @Test
    void notEndedYet_skips() {
        Booking b = bankBooking(LocalTime.of(23, 59), LocalDate.now().plusDays(1), host()); // tương lai
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));

        service.settleBookingsToProvider();

        verifyNoInteractions(refundService);
        verify(bankAccountService, never()).getDefault(any());
    }

    @Test
    void cashBooking_skips() {
        Booking b = bankBooking(LocalTime.of(8, 0), LocalDate.now().minusDays(1), host());
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.CASH).build()));

        service.settleBookingsToProvider();

        verify(refundService, never()).issueCashRefund(any(), any(), any(), any(), any(), any());
        verify(providerDebtRepository, never()).save(any());
    }

    @Test
    void endedBankBooking_noBank_recordsCredit() {
        User host = host();
        Booking b = bankBooking(LocalTime.of(8, 0), LocalDate.now().minusDays(1), host);
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.BANK).build()));
        when(refundService.findBySource(eq(RefundSourceType.BOOKING_PAYOUT), anyString()))
                .thenReturn(Optional.empty());
        when(providerDebtRepository.existsBySourceBookingId(anyString())).thenReturn(false);
        when(bankAccountService.getDefault(host.getUserId())).thenReturn(Optional.empty());

        service.settleBookingsToProvider();

        verify(refundService, never()).issueCashRefund(any(), any(), any(), any(), any(), any());
        verify(providerDebtRepository).save(any(ProviderDebt.class));
    }

    @Test
    void alreadySettled_skips() {
        Booking b = bankBooking(LocalTime.of(8, 0), LocalDate.now().minusDays(1), host());
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.BANK).build()));
        when(refundService.findBySource(RefundSourceType.BOOKING_PAYOUT, b.getBookingId().toString()))
                .thenReturn(Optional.of(new com.example.FieldFinder.entity.RefundRequest()));

        service.settleBookingsToProvider();

        verify(bankAccountService, never()).getDefault(any());
        verify(refundService, never()).issueCashRefund(any(), any(), any(), any(), any(), any());
        verify(providerDebtRepository, never()).save(any());
    }
}
