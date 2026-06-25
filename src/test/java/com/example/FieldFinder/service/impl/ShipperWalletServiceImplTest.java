package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.ShipperWalletTxnType;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.ShipperWallet;
import com.example.FieldFinder.entity.ShipperWalletTransaction;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ShipperWalletRepository;
import com.example.FieldFinder.repository.ShipperWalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipperWalletServiceImplTest {

    @Mock ShipperWalletRepository walletRepository;
    @Mock ShipperWalletTransactionRepository txRepository;

    @InjectMocks ShipperWalletServiceImpl service;

    private User shipper;
    private UUID sid;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "blockGraceDays", 7L);
        ReflectionTestUtils.setField(service, "withdrawDeadlineHours", 24L);
        ReflectionTestUtils.setField(service, "minWithdraw", new BigDecimal("10000"));
        sid = UUID.randomUUID();
        shipper = new User();
        shipper.setUserId(sid);
    }

    private ShipperWallet wallet(BigDecimal balance) {
        return ShipperWallet.builder().shipper(shipper).balance(balance).build();
    }

    /** Bơm sẵn 1 ví dùng chung cho mọi lần ghi sổ trong test. */
    private ShipperWallet stubWallet(BigDecimal start) {
        ShipperWallet w = wallet(start);
        when(walletRepository.findByShipper_UserId(sid)).thenReturn(Optional.of(w));
        when(walletRepository.findByUserIdForUpdate(sid)).thenReturn(Optional.of(w));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(txRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        return w;
    }

    @Test
    void credit_increasesBalance_andRecordsTxn() {
        ShipperWallet w = stubWallet(BigDecimal.ZERO);
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(false);

        ArgumentCaptor<ShipperWalletTransaction> cap = ArgumentCaptor.forClass(ShipperWalletTransaction.class);
        service.credit(shipper, ShipperWalletTxnType.SHIP_EARNING, new BigDecimal("25000"),
                "ORDER", "10", "thu nhập ship");

        assertThat(w.getBalance()).isEqualByComparingTo("25000");
        verify(txRepository).save(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualByComparingTo("25000");
        assertThat(cap.getValue().getBalanceAfter()).isEqualByComparingTo("25000");
    }

    @Test
    void debit_beyondBalance_goesNegative_setsNegativeSince() {
        ShipperWallet w = stubWallet(new BigDecimal("25000"));
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(false);

        service.debit(shipper, ShipperWalletTxnType.COD_COLLECTED, new BigDecimal("300000"),
                "ORDER", "10", "tiền hàng COD");

        assertThat(w.getBalance()).isEqualByComparingTo("-275000");
        assertThat(w.getNegativeSince()).isNotNull();
    }

    @Test
    void credit_idempotent_skipsWhenSourceExists() {
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(
                ShipperWalletTxnType.SHIP_EARNING, "ORDER", "10")).thenReturn(true);

        ShipperWalletTransaction tx = service.credit(shipper, ShipperWalletTxnType.SHIP_EARNING,
                new BigDecimal("25000"), "ORDER", "10", "thu nhập ship");

        assertThat(tx).isNull();
        verify(walletRepository, never()).save(any());
        verify(txRepository, never()).save(any());
    }

    @Test
    void settleDelivery_bankOrder_creditsShipOnly() {
        ShipperWallet w = stubWallet(BigDecimal.ZERO);
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(false);
        Order order = Order.builder()
                .orderId(10L).shipper(shipper).paymentMethod(PaymentMethod.BANK)
                .grossShippingFee(25000.0).shippingFee(25000.0).totalAmount(525000.0)
                .build();

        service.settleDelivery(order);

        // chỉ ghi 1 dòng: SHIP_EARNING +25000 (không có COD vì đơn BANK)
        assertThat(w.getBalance()).isEqualByComparingTo("25000");
        verify(txRepository, times(1)).save(any());
    }

    @Test
    void settleDelivery_cashOrder_creditsShip_andDebitsCod() {
        ShipperWallet w = stubWallet(BigDecimal.ZERO);
        when(txRepository.existsByTypeAndSourceTypeAndSourceId(any(), any(), any())).thenReturn(false);
        // freeship cho khách (shippingFee=0) nhưng shipper vẫn hưởng grossShippingFee; thu hộ = total - 0
        Order order = Order.builder()
                .orderId(11L).shipper(shipper).paymentMethod(PaymentMethod.CASH)
                .grossShippingFee(25000.0).shippingFee(0.0).totalAmount(500000.0)
                .build();

        service.settleDelivery(order);

        // +25000 ship − 500000 tiền hàng thu hộ = −475000 (công nợ COD)
        assertThat(w.getBalance()).isEqualByComparingTo("-475000");
        verify(txRepository, times(2)).save(any());
    }

    @Test
    void computeWithdrawable_zeroWhenNegative() {
        when(walletRepository.findByShipper_UserId(sid))
                .thenReturn(Optional.of(wallet(new BigDecimal("-475000"))));
        assertThat(service.computeWithdrawable(sid)).isEqualByComparingTo("0");
    }

    @Test
    void computeWithdrawable_balanceWhenPositive() {
        when(walletRepository.findByShipper_UserId(sid))
                .thenReturn(Optional.of(wallet(new BigDecimal("80000"))));
        assertThat(service.computeWithdrawable(sid)).isEqualByComparingTo("80000");
    }

    @Test
    void getMinWithdraw_returnsConfiguredFloor() {
        assertThat(service.getMinWithdraw()).isEqualByComparingTo("10000");
    }

    @Test
    void isBlocked_whenNegativeOverdue() {
        when(walletRepository.existsByShipper_UserIdAndBalanceLessThanAndNegativeSinceBefore(
                eq(sid), eq(BigDecimal.ZERO), any(LocalDateTime.class))).thenReturn(true);
        assertThat(service.isBlocked(sid)).isTrue();
    }

    @Test
    void listTransactions_delegatesToRepo() {
        when(txRepository.findByShipper_UserIdOrderByCreatedAtDesc(sid)).thenReturn(List.of());
        assertThat(service.listTransactions(sid)).isEmpty();
    }
}
