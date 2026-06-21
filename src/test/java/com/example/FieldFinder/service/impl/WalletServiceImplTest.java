package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderWallet;
import com.example.FieldFinder.entity.WalletTransaction;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.ProviderWalletRepository;
import com.example.FieldFinder.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {

    @Mock ProviderWalletRepository walletRepository;
    @Mock WalletTransactionRepository txRepository;
    @Mock BookingRepository bookingRepository;

    @InjectMocks WalletServiceImpl service;

    private Provider provider;
    private UUID pid;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "reserveRate", new BigDecimal("0.10"));
        ReflectionTestUtils.setField(service, "blockGraceDays", 7L);
        ReflectionTestUtils.setField(service, "withdrawDeadlineHours", 24L);
        ReflectionTestUtils.setField(service, "minWithdraw", new BigDecimal("10000"));
        pid = UUID.randomUUID();
        provider = new Provider();
        provider.setProviderId(pid);
    }

    private ProviderWallet wallet(BigDecimal balance) {
        return ProviderWallet.builder().provider(provider).balance(balance).build();
    }

    @Test
    void getOrCreate_raceLost_rereadsExistingInsteadOfThrowing() {
        // Tx song song đã tạo ví ⇒ insert tx-mới ném DataIntegrityViolationException (uk_wallet_provider).
        WalletServiceImpl self = mock(WalletServiceImpl.class);
        ReflectionTestUtils.setField(service, "self", self);
        ProviderWallet existing = wallet(new BigDecimal("250000"));
        when(self.insertWalletInNewTx(provider))
                .thenThrow(new DataIntegrityViolationException("uk_wallet_provider"));
        when(walletRepository.findByProvider_ProviderId(pid))
                .thenReturn(Optional.empty())   // lần đầu: chưa thấy
                .thenReturn(Optional.of(existing)); // sau khi thua race: đọc lại thấy

        assertThat(service.getOrCreate(provider)).isSameAs(existing);
        verify(walletRepository, never()).save(any()); // không tự save ở tx ngoài
    }

    @Test
    void credit_increasesBalance_andRecordsTxn() {
        ProviderWallet w = wallet(BigDecimal.ZERO);
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(false);
        when(walletRepository.findByProvider_ProviderId(pid)).thenReturn(Optional.of(w));
        when(walletRepository.findByProviderIdForUpdate(pid)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(txRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ArgumentCaptor<WalletTransaction> cap = ArgumentCaptor.forClass(WalletTransaction.class);
        service.credit(provider, WalletTxnType.BOOKING_REVENUE, new BigDecimal("100000"),
                "BOOKING", "b1", "doanh thu");

        assertThat(w.getBalance()).isEqualByComparingTo("100000");
        verify(txRepository).save(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualByComparingTo("100000");
        assertThat(cap.getValue().getBalanceAfter()).isEqualByComparingTo("100000");
    }

    @Test
    void debit_beyondBalance_goesNegative_setsNegativeSince() {
        ProviderWallet w = wallet(new BigDecimal("100000"));
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(false);
        when(walletRepository.findByProvider_ProviderId(pid)).thenReturn(Optional.of(w));
        when(walletRepository.findByProviderIdForUpdate(pid)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(txRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.debit(provider, WalletTxnType.CANCEL_PENALTY, new BigDecimal("150000"),
                "BOOKING", "b1", "phạt");

        assertThat(w.getBalance()).isEqualByComparingTo("-50000");
        assertThat(w.getNegativeSince()).isNotNull();
    }

    @Test
    void credit_idempotent_skipsWhenSourceExists() {
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(
                WalletTxnType.BOOKING_REVENUE, "BOOKING", "b1")).thenReturn(true);

        WalletTransaction tx = service.credit(provider, WalletTxnType.BOOKING_REVENUE,
                new BigDecimal("100000"), "BOOKING", "b1", "doanh thu");

        assertThat(tx).isNull();
        verify(walletRepository, never()).save(any());
        verify(txRepository, never()).save(any());
    }

    @Test
    void computeWithdrawable_balanceMinusReserve() {
        when(walletRepository.findByProvider_ProviderId(pid))
                .thenReturn(Optional.of(wallet(new BigDecimal("500000"))));
        when(bookingRepository.sumUpcomingConfirmedByProvider(eq(pid), any(LocalDate.class)))
                .thenReturn(new BigDecimal("1000000")); // reserve = 10% = 100000

        assertThat(service.computeReserve(pid)).isEqualByComparingTo("100000");
        assertThat(service.computeWithdrawable(pid)).isEqualByComparingTo("400000"); // 500000 - 100000
    }

    @Test
    void getMinWithdraw_returnsConfiguredFloor() {
        assertThat(service.getMinWithdraw()).isEqualByComparingTo("10000");
    }

    @Test
    void getMinWithdraw_nullConfig_fallsBackToZero() {
        ReflectionTestUtils.setField(service, "minWithdraw", null);
        assertThat(service.getMinWithdraw()).isEqualByComparingTo("0");
    }

    @Test
    void isBlocked_whenNegativeOverdue() {
        when(walletRepository.existsByProvider_ProviderIdAndBalanceLessThanAndNegativeSinceBefore(
                eq(pid), eq(BigDecimal.ZERO), any(LocalDateTime.class))).thenReturn(true);

        assertThat(service.isBlocked(pid)).isTrue();
    }
}
