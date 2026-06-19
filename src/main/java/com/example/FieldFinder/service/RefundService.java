package com.example.FieldFinder.service;

import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.entity.BankAccount;
import com.example.FieldFinder.entity.Discount;
import com.example.FieldFinder.entity.RefundRequest;
import com.example.FieldFinder.entity.User;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface RefundService {

    RefundRequest issueRefundCredit(User user,
                                    RefundSourceType sourceType,
                                    String sourceId,
                                    BigDecimal amount,
                                    String reason);

    /**
     * Như bản 5 tham số, nhưng mã phát hành bị giới hạn: chỉ dùng đặt sân
     * của provider {@code restrictProviderId} (null = không giới hạn).
     */
    RefundRequest issueRefundCredit(User user,
                                    RefundSourceType sourceType,
                                    String sourceId,
                                    BigDecimal amount,
                                    String reason,
                                    UUID restrictProviderId);

    /**
     * Hoàn TIỀN MẶT về TK ngân hàng (PayOS payout). Tạo bản ghi PAYOUT_PENDING,
     * job nền sẽ đẩy lệnh chi + poll trạng thái. Idempotent theo (sourceType, sourceId).
     *
     * @param bankAccount TK nhận tiền (mặc định của user), bắt buộc.
     */
    RefundRequest issueCashRefund(User user,
                                  RefundSourceType sourceType,
                                  String sourceId,
                                  BigDecimal amount,
                                  String reason,
                                  BankAccount bankAccount);

    /**
     * Bù voucher cho một refund tiền mặt đã QUÁ HẠN mà chưa chi xong.
     * Gắn mã REFUND_CREDIT vào chính bản ghi đó (không tạo refund mới ⇒ không vướng
     * idempotency), đặt status ISSUED + method VOUCHER. Chỉ gọi cho khoản chưa gửi PayOS.
     */
    RefundRequest fallbackToVoucher(RefundRequest existing, String note);

    int DEFAULT_EXPIRY_DAYS = 90;

    String CODE_PREFIX = "RF-";

    Discount generateRefundDiscount(BigDecimal amount);

    Optional<RefundRequest> findBySource(RefundSourceType type, String sourceId);
}