package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.Enum.WalletTxnType;
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
import com.example.FieldFinder.service.WalletService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Settlement giờ CỘNG VÍ chủ sân (BOOKING_REVENUE) đã trừ hoa hồng, không payout thẳng nữa. */
@ExtendWith(MockitoExtension.class)
class BookingServiceSettlementTest {

    @Mock BookingRepository bookingRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock WalletService walletService;

    @InjectMocks BookingServiceImpl service;

    private Provider provider;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "payoutCommissionRate", new BigDecimal("0.05"));
        provider = new Provider();
        provider.setProviderId(UUID.randomUUID());
        User host = new User();
        host.setUserId(UUID.randomUUID());
        provider.setUser(host);
    }

    private Booking bankBooking(LocalTime slotEnd, LocalDate date) {
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

    @Test
    void endedBankBooking_creditsWalletMinusCommission() {
        Booking b = bankBooking(LocalTime.of(8, 0), LocalDate.now().minusDays(1)); // hôm qua → đá xong
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.BANK).build()));

        service.settleBookingsToProvider();

        // 200000 × (1 − 0.05) = 190000 vào ví
        verify(walletService).credit(eq(provider), eq(WalletTxnType.BOOKING_REVENUE),
                eq(new BigDecimal("190000")), eq("BOOKING"), eq(b.getBookingId().toString()), anyString());
    }

    @Test
    void notEndedYet_skips() {
        Booking b = bankBooking(LocalTime.of(23, 59), LocalDate.now().plusDays(1)); // tương lai
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));

        service.settleBookingsToProvider();

        verifyNoInteractions(walletService);
    }

    @Test
    void cashBooking_skips() {
        Booking b = bankBooking(LocalTime.of(8, 0), LocalDate.now().minusDays(1));
        when(bookingRepository.findAllByStatus(BookingStatus.CONFIRMED)).thenReturn(List.of(b));
        when(paymentRepository.findFirstByBooking_BookingIdOrderByCreatedAtDesc(b.getBookingId()))
                .thenReturn(Optional.of(Payment.builder().paymentMethod(PaymentMethod.CASH).build()));

        service.settleBookingsToProvider();

        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
    }
}
