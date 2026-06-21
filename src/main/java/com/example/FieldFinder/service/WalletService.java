package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.WalletTxnType;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Provider;
import com.example.FieldFinder.entity.ProviderWallet;
import com.example.FieldFinder.entity.WalletTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Sổ cái ví chủ sân: cộng/trừ số dư + sao kê, reserve động, chặn khi âm quá hạn. */
public interface WalletService {

    ProviderWallet getOrCreate(Provider provider);

    /** Cộng tiền vào ví (idempotent theo type+source). Trả null nếu đã ghi/không hợp lệ. */
    WalletTransaction credit(Provider provider, WalletTxnType type, BigDecimal amount,
                             String sourceType, String sourceId, String reason);

    /** Trừ tiền khỏi ví (idempotent theo type+source). Số dư có thể âm. */
    WalletTransaction debit(Provider provider, WalletTxnType type, BigDecimal amount,
                            String sourceType, String sourceId, String reason);

    BigDecimal getBalance(UUID providerId);

    /** Reserve động = reserve-rate × tổng đơn CONFIRMED+PAID chưa đá xong (đệm cho phạt hủy). */
    BigDecimal computeReserve(UUID providerId);

    /** Rút được = max(0, số dư − reserve). */
    BigDecimal computeWithdrawable(UUID providerId);

    /** Số tiền tối thiểu mỗi lệnh rút (sàn chống rút lẻ + phí payout). */
    BigDecimal getMinWithdraw();

    boolean isBlocked(UUID providerId);

    /** Thời điểm ví bắt đầu âm (null nếu ≥0) — để FE đếm ngược hạn trước khi bị chặn. */
    java.time.LocalDateTime getNegativeSince(UUID providerId);

    /** Số ngày grace trước khi ví âm bị chặn nhận booking. */
    long getBlockGraceDays();

    List<WalletTransaction> listTransactions(UUID providerId);

    /** Tạo lệnh rút (trừ ví ngay, status PENDING) — WalletPayoutProcessor sẽ chi qua PayOS. */
    WalletTransaction createWithdrawal(Provider provider, BigDecimal amount, BankAccount bank);

    /** Hoàn lại số dư khi lệnh rút thất bại vĩnh viễn. */
    void reverseFailedWithdrawal(WalletTransaction withdrawal);
}
