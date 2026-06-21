package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.WalletTxnStatus;
import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderWallet;
import com.example.FieldFinder.entity.WalletTransaction;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.ProviderWalletRepository;
import com.example.FieldFinder.repository.WalletTransactionRepository;
import com.example.FieldFinder.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final ProviderWalletRepository walletRepository;
    private final WalletTransactionRepository txRepository;
    private final BookingRepository bookingRepository;

    /** Self-proxy để gọi {@link #insertWalletInNewTx} với REQUIRES_NEW (self-invoke không kích hoạt AOP). */
    @Lazy
    @Autowired
    private WalletServiceImpl self;

    /** Tỷ lệ reserve = phần phạt hủy sát giờ (PROVIDER_LATE_CANCEL_RATE − 1 = 0.10). */
    @Value("${provider.wallet.reserve-rate:0.10}")
    private BigDecimal reserveRate;

    /** Ví âm quá số ngày này ⇒ chặn nhận booking. */
    @Value("${provider.wallet.block-grace-days:7}")
    private long blockGraceDays;

    /** Hạn xử lý 1 lệnh rút (giờ). */
    @Value("${provider.wallet.withdraw-deadline-hours:24}")
    private long withdrawDeadlineHours;

    /** Sàn rút tối thiểu mỗi lệnh (chống rút lẻ + phí payout). */
    @Value("${provider.wallet.min-withdraw:10000}")
    private BigDecimal minWithdraw;

    @Override
    @Transactional
    public ProviderWallet getOrCreate(Provider provider) {
        if (provider == null) throw new IllegalArgumentException("Provider required");
        UUID providerId = provider.getProviderId();
        return walletRepository.findByProvider_ProviderId(providerId)
                .orElseGet(() -> {
                    try {
                        // Insert trong tx riêng: nếu race thua, chỉ tx con bị rollback, tx ngoài còn sạch.
                        return self.insertWalletInNewTx(provider);
                    } catch (DataIntegrityViolationException raceLost) {
                        // Tx song song đã tạo ví trước (uk_wallet_provider) ⇒ đọc lại trong tx ngoài.
                        return walletRepository.findByProvider_ProviderId(providerId)
                                .orElseThrow(() -> raceLost);
                    }
                });
    }

    /**
     * Tạo ví trong giao dịch MỚI để cô lập va chạm khóa duy nhất {@code uk_wallet_provider}.
     * Phải gọi qua proxy ({@code self.insertWalletInNewTx}); flush ngay để lỗi trùng nổi lên dưới dạng
     * {@link DataIntegrityViolationException} mà không đầu độc giao dịch của bên gọi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProviderWallet insertWalletInNewTx(Provider provider) {
        return walletRepository.saveAndFlush(ProviderWallet.builder()
                .provider(provider)
                .balance(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional
    public WalletTransaction credit(Provider provider, WalletTxnType type, BigDecimal amount,
                                    String sourceType, String sourceId, String reason) {
        if (amount == null || amount.signum() <= 0) return null;
        return post(provider, type, amount.abs(), sourceType, sourceId, reason, WalletTxnStatus.COMPLETED);
    }

    @Override
    @Transactional
    public WalletTransaction debit(Provider provider, WalletTxnType type, BigDecimal amount,
                                   String sourceType, String sourceId, String reason) {
        if (amount == null || amount.signum() <= 0) return null;
        return post(provider, type, amount.abs().negate(), sourceType, sourceId, reason, WalletTxnStatus.COMPLETED);
    }

    /** Ghi 1 dòng sổ + cập nhật số dư (idempotent theo type+source cho giao dịch không phải rút). */
    private WalletTransaction post(Provider provider, WalletTxnType type, BigDecimal signedAmount,
                                   String sourceType, String sourceId, String reason,
                                   WalletTxnStatus status) {
        if (provider == null) throw new IllegalArgumentException("Provider required");
        if (signedAmount == null || signedAmount.signum() == 0) return null;
        if (type != WalletTxnType.WITHDRAWAL && sourceType != null && sourceId != null
                && txRepository.existsByTypeAndSourceTypeAndSourceId(type, sourceType, sourceId)) {
            return null; // đã ghi cho nguồn này
        }

        getOrCreate(provider); // đảm bảo ví tồn tại trước khi khóa
        ProviderWallet wallet = walletRepository.findByProviderIdForUpdate(provider.getProviderId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy ví chủ sân!"));

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

        WalletTransaction tx = WalletTransaction.builder()
                .provider(provider)
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
    public BigDecimal getBalance(UUID providerId) {
        return walletRepository.findByProvider_ProviderId(providerId)
                .map(ProviderWallet::getBalance).orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal computeReserve(UUID providerId) {
        BigDecimal upcoming = bookingRepository.sumUpcomingConfirmedByProvider(providerId, LocalDate.now());
        if (upcoming == null) upcoming = BigDecimal.ZERO;
        BigDecimal rate = reserveRate == null ? BigDecimal.ZERO : reserveRate;
        return upcoming.multiply(rate).setScale(0, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal computeWithdrawable(UUID providerId) {
        BigDecimal balance = getBalance(providerId);
        BigDecimal w = balance.subtract(computeReserve(providerId));
        return w.signum() > 0 ? w : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getMinWithdraw() {
        return minWithdraw == null ? BigDecimal.ZERO : minWithdraw;
    }

    @Override
    public boolean isBlocked(UUID providerId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(blockGraceDays);
        return walletRepository.existsByProvider_ProviderIdAndBalanceLessThanAndNegativeSinceBefore(
                providerId, BigDecimal.ZERO, cutoff);
    }

    @Override
    public LocalDateTime getNegativeSince(UUID providerId) {
        return walletRepository.findByProvider_ProviderId(providerId)
                .map(ProviderWallet::getNegativeSince).orElse(null);
    }

    @Override
    public long getBlockGraceDays() {
        return blockGraceDays;
    }

    @Override
    public List<WalletTransaction> listTransactions(UUID providerId) {
        return txRepository.findByProvider_ProviderIdOrderByCreatedAtDesc(providerId);
    }

    @Override
    @Transactional
    public WalletTransaction createWithdrawal(Provider provider, BigDecimal amount, BankAccount bank) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Số tiền rút không hợp lệ");
        if (bank == null) throw new IllegalArgumentException("Cần TK ngân hàng để rút");
        WalletTransaction tx = post(provider, WalletTxnType.WITHDRAWAL, amount.negate(),
                "WITHDRAWAL", null, "Rút tiền về tài khoản ngân hàng", WalletTxnStatus.PENDING);
        if (tx == null) throw new RuntimeException("Không tạo được lệnh rút");
        tx.setBankBin(bank.getBankBin());
        tx.setBankAccountNumber(bank.getAccountNumber());
        tx.setPayosReferenceId("WD" + tx.getTxnId().toString().replace("-", ""));
        tx.setDeadlineAt(LocalDateTime.now().plusHours(withdrawDeadlineHours));
        tx.setSourceId(tx.getTxnId().toString());
        return txRepository.save(tx);
    }

    @Override
    @Transactional
    public void reverseFailedWithdrawal(WalletTransaction withdrawal) {
        if (withdrawal == null || withdrawal.getType() != WalletTxnType.WITHDRAWAL) return;
        if (withdrawal.getStatus() == WalletTxnStatus.FAILED) return; // đã hoàn
        // cộng lại đúng số đã trừ (amount âm ⇒ cộng lại trị tuyệt đối)
        post(withdrawal.getProvider(), WalletTxnType.ADJUSTMENT, withdrawal.getAmount().abs(),
                "WITHDRAWAL_REVERSAL", withdrawal.getTxnId().toString(),
                "Hoàn số dư do rút tiền thất bại", WalletTxnStatus.COMPLETED);
        withdrawal.setStatus(WalletTxnStatus.FAILED);
        withdrawal.setProcessedAt(LocalDateTime.now());
        txRepository.save(withdrawal);
    }
}
