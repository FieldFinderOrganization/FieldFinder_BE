package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.ShipperWalletTxnType;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.ShipperWallet;
import com.example.FieldFinder.entity.ShipperWalletTransaction;
import com.example.FieldFinder.entity.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Sổ cái ví shipper: cộng/trừ số dư + sao kê, tự rút về TK, chặn khi công nợ COD quá hạn. */
public interface ShipperWalletService {

    ShipperWallet getOrCreate(User shipper);

    /** Cộng tiền vào ví (idempotent theo type+source). Trả null nếu đã ghi/không hợp lệ. */
    ShipperWalletTransaction credit(User shipper, ShipperWalletTxnType type, BigDecimal amount,
                                    String sourceType, String sourceId, String reason);

    /** Trừ tiền khỏi ví (idempotent theo type+source). Số dư có thể âm (công nợ). */
    ShipperWalletTransaction debit(User shipper, ShipperWalletTxnType type, BigDecimal amount,
                                   String sourceType, String sourceId, String reason);

    /**
     * Đối soát ví khi đơn giao xong (DELIVERED): cộng phí ship gốc; nếu đơn CASH (COD)
     * trừ thêm tiền hàng shipper thu hộ. Idempotent theo orderId — gọi lại không ghi 2 lần.
     */
    void settleDelivery(Order order);

    BigDecimal getBalance(UUID shipperId);

    /** Rút được = max(0, số dư). Công nợ COD (số dư âm) ⇒ rút được = 0. */
    BigDecimal computeWithdrawable(UUID shipperId);

    /** Số tiền tối thiểu mỗi lệnh rút (sàn chống rút lẻ + phí payout). */
    BigDecimal getMinWithdraw();

    boolean isBlocked(UUID shipperId);

    /** Thời điểm ví bắt đầu âm (null nếu ≥0) — để FE đếm ngược hạn trước khi bị chặn. */
    java.time.LocalDateTime getNegativeSince(UUID shipperId);

    /** Số ngày grace trước khi công nợ âm bị chặn nhận đơn. */
    long getBlockGraceDays();

    List<ShipperWalletTransaction> listTransactions(UUID shipperId);

    /** Tạo lệnh rút (trừ ví ngay, status PENDING) — ShipperWalletPayoutProcessor sẽ chi qua PayOS. */
    ShipperWalletTransaction createWithdrawal(User shipper, BigDecimal amount, BankAccount bank);

    /** Hoàn lại số dư khi lệnh rút thất bại vĩnh viễn. */
    void reverseFailedWithdrawal(ShipperWalletTransaction withdrawal);
}
