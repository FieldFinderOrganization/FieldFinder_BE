package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.ShipperWalletTxnType;
import com.example.FieldFinder.Enum.WalletTxnStatus;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.ShipperWallet;
import com.example.FieldFinder.entity.ShipperWalletTransaction;
import com.example.FieldFinder.entity.User;
import com.example.FieldFinder.repository.ShipperWalletRepository;
import com.example.FieldFinder.repository.ShipperWalletTransactionRepository;
import com.example.FieldFinder.service.ShipperWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShipperWalletServiceImpl implements ShipperWalletService {

    private final ShipperWalletRepository walletRepository;
    private final ShipperWalletTransactionRepository txRepository;

    /** Self-proxy để gọi {@link #insertWalletInNewTx} với REQUIRES_NEW (self-invoke không kích hoạt AOP). */
    @Lazy
    @Autowired
    private ShipperWalletServiceImpl self;

    /** Ví âm (công nợ COD) quá số ngày này ⇒ chặn nhận đơn. */
    @Value("${shipper.wallet.block-grace-days:7}")
    private long blockGraceDays;

    /** Hạn xử lý 1 lệnh rút (giờ). */
    @Value("${shipper.wallet.withdraw-deadline-hours:24}")
    private long withdrawDeadlineHours;

    /** Sàn rút tối thiểu mỗi lệnh (chống rút lẻ + phí payout). */
    @Value("${shipper.wallet.min-withdraw:10000}")
    private BigDecimal minWithdraw;

    @Override
    @Transactional
    public ShipperWallet getOrCreate(User shipper) {
        if (shipper == null) throw new IllegalArgumentException("Shipper required");
        UUID userId = shipper.getUserId();
        return walletRepository.findByShipper_UserId(userId)
                .orElseGet(() -> {
                    try {
                        // Insert trong tx riêng: nếu race thua, chỉ tx con bị rollback, tx ngoài còn sạch.
                        return self.insertWalletInNewTx(shipper);
                    } catch (DataIntegrityViolationException raceLost) {
                        // Tx song song đã tạo ví trước (uk_shipper_wallet_user) ⇒ đọc lại trong tx ngoài.
                        return walletRepository.findByShipper_UserId(userId)
                                .orElseThrow(() -> raceLost);
                    }
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShipperWallet insertWalletInNewTx(User shipper) {
        return walletRepository.saveAndFlush(ShipperWallet.builder()
                .shipper(shipper)
                .balance(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional
    public ShipperWalletTransaction credit(User shipper, ShipperWalletTxnType type, BigDecimal amount,
                                           String sourceType, String sourceId, String reason) {
        if (amount == null || amount.signum() <= 0) return null;
        return post(shipper, type, amount.abs(), sourceType, sourceId, reason, WalletTxnStatus.COMPLETED);
    }

    @Override
    @Transactional
    public ShipperWalletTransaction debit(User shipper, ShipperWalletTxnType type, BigDecimal amount,
                                          String sourceType, String sourceId, String reason) {
        if (amount == null || amount.signum() <= 0) return null;
        return post(shipper, type, amount.abs().negate(), sourceType, sourceId, reason, WalletTxnStatus.COMPLETED);
    }

    @Override
    @Transactional
    public void settleDelivery(Order order) {
        if (order == null || order.getShipper() == null) return;
        User shipper = order.getShipper();
        String orderId = String.valueOf(order.getOrderId());

        // Thu nhập = phí ship gốc; đơn cũ chưa có grossShippingFee thì fallback shippingFee.
        double ship = order.getGrossShippingFee() != null
                ? order.getGrossShippingFee()
                : (order.getShippingFee() != null ? order.getShippingFee() : 0.0);
        if (ship > 0) {
            credit(shipper, ShipperWalletTxnType.SHIP_EARNING, BigDecimal.valueOf(ship),
                    "ORDER", orderId, "Thu nhập ship đơn #" + orderId);
        }

        // COD: shipper thu hộ tiền hàng (= tổng − phí ship khách trả) ⇒ ghi công nợ phải nộp.
        if (order.getPaymentMethod() == PaymentMethod.CASH) {
            double total = order.getTotalAmount() != null ? order.getTotalAmount() : 0.0;
            double paidShip = order.getShippingFee() != null ? order.getShippingFee() : 0.0;
            double goods = total - paidShip;
            if (goods > 0) {
                debit(shipper, ShipperWalletTxnType.COD_COLLECTED, BigDecimal.valueOf(goods),
                        "ORDER", orderId, "Tiền hàng thu hộ COD đơn #" + orderId);
            }
        }
    }

    /** Ghi 1 dòng sổ + cập nhật số dư (idempotent theo type+source cho giao dịch không phải rút). */
    private ShipperWalletTransaction post(User shipper, ShipperWalletTxnType type, BigDecimal signedAmount,
                                          String sourceType, String sourceId, String reason,
                                          WalletTxnStatus status) {
        if (shipper == null) throw new IllegalArgumentException("Shipper required");
        if (signedAmount == null || signedAmount.signum() == 0) return null;
        if (type != ShipperWalletTxnType.WITHDRAWAL && sourceType != null && sourceId != null
                && txRepository.existsByTypeAndSourceTypeAndSourceId(type, sourceType, sourceId)) {
            return null; // đã ghi cho nguồn này
        }

        getOrCreate(shipper); // đảm bảo ví tồn tại trước khi khóa
        ShipperWallet wallet = walletRepository.findByUserIdForUpdate(shipper.getUserId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy ví shipper!"));

        BigDecimal newBalance = wallet.getBalance().add(signedAmount);
        LocalDateTime now = LocalDateTime.now();
        if (newBalance.signum() < 0) {
            if (wallet.getNegativeSince() == null) wallet.setNegativeSince(now);
        } else {
            wallet.setNegativeSince(null);
        }
        wallet.setBalance(newBalance);
        wallet.setUpdatedAt(now);
        walletRepository.save(wallet);

        ShipperWalletTransaction tx = ShipperWalletTransaction.builder()
                .shipper(shipper)
                .type(type)
                .amount(signedAmount)
                .balanceAfter(newBalance)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .reason(reason)
                .status(status)
                .createdAt(now)
                .build();
        return txRepository.save(tx);
    }

    @Override
    public BigDecimal getBalance(UUID shipperId) {
        return walletRepository.findByShipper_UserId(shipperId)
                .map(ShipperWallet::getBalance).orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal computeWithdrawable(UUID shipperId) {
        BigDecimal balance = getBalance(shipperId);
        return balance.signum() > 0 ? balance : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getMinWithdraw() {
        return minWithdraw == null ? BigDecimal.ZERO : minWithdraw;
    }

    @Override
    public boolean isBlocked(UUID shipperId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(blockGraceDays);
        return walletRepository.existsByShipper_UserIdAndBalanceLessThanAndNegativeSinceBefore(
                shipperId, BigDecimal.ZERO, cutoff);
    }

    @Override
    public LocalDateTime getNegativeSince(UUID shipperId) {
        return walletRepository.findByShipper_UserId(shipperId)
                .map(ShipperWallet::getNegativeSince).orElse(null);
    }

    @Override
    public long getBlockGraceDays() {
        return blockGraceDays;
    }

    @Override
    public List<ShipperWalletTransaction> listTransactions(UUID shipperId) {
        return txRepository.findByShipper_UserIdOrderByCreatedAtDesc(shipperId);
    }

    @Override
    @Transactional
    public ShipperWalletTransaction createWithdrawal(User shipper, BigDecimal amount, BankAccount bank) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Số tiền rút không hợp lệ");
        if (bank == null) throw new IllegalArgumentException("Cần TK ngân hàng để rút");
        ShipperWalletTransaction tx = post(shipper, ShipperWalletTxnType.WITHDRAWAL, amount.negate(),
                "WITHDRAWAL", null, "Rút tiền về tài khoản ngân hàng", WalletTxnStatus.PENDING);
        if (tx == null) throw new RuntimeException("Không tạo được lệnh rút");
        tx.setBankBin(bank.getBankBin());
        tx.setBankAccountNumber(bank.getAccountNumber());
        tx.setPayosReferenceId("SWD" + tx.getTxnId().toString().replace("-", ""));
        tx.setDeadlineAt(LocalDateTime.now().plusHours(withdrawDeadlineHours));
        tx.setSourceId(tx.getTxnId().toString());
        return txRepository.save(tx);
    }

    @Override
    @Transactional
    public void reverseFailedWithdrawal(ShipperWalletTransaction withdrawal) {
        if (withdrawal == null || withdrawal.getType() != ShipperWalletTxnType.WITHDRAWAL) return;
        if (withdrawal.getStatus() == WalletTxnStatus.FAILED) return; // đã hoàn
        // cộng lại đúng số đã trừ (amount âm ⇒ cộng lại trị tuyệt đối)
        post(withdrawal.getShipper(), ShipperWalletTxnType.ADJUSTMENT, withdrawal.getAmount().abs(),
                "WITHDRAWAL_REVERSAL", withdrawal.getTxnId().toString(),
                "Hoàn số dư do rút tiền thất bại", WalletTxnStatus.COMPLETED);
        withdrawal.setStatus(WalletTxnStatus.FAILED);
        withdrawal.setProcessedAt(LocalDateTime.now());
        txRepository.save(withdrawal);
    }
}
