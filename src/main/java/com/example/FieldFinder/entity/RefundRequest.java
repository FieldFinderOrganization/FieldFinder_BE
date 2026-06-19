package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.RefundMethod;
import com.example.FieldFinder.Enum.RefundSourceType;
import com.example.FieldFinder.Enum.RefundStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "RefundRequests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refund_source",
                columnNames = {"SourceType", "SourceId"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "RefundId")
    private UUID refundId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "SourceType", nullable = false, length = 20)
    private RefundSourceType sourceType;

    /** orderId (Long) hoặc bookingId (UUID) — lưu dạng String để generic. */
    @Column(name = "SourceId", nullable = false, length = 64)
    private String sourceId;

    @Column(name = "Amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "Reason", length = 1000)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "IssuedDiscountId")
    private Discount issuedDiscount;

    /** VOUCHER (mặc định, luồng cũ) hoặc CASH (hoàn về TK ngân hàng qua PayOS payout). */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "RefundMethod", nullable = false, length = 16)
    private RefundMethod refundMethod = RefundMethod.VOUCHER;

    // ----- Thông tin payout (chỉ dùng khi refundMethod = CASH) -----

    /** Snapshot TK nhận tại thời điểm hoàn (user có thể đổi TK sau). */
    @Column(name = "BankBin", length = 12)
    private String bankBin;

    @Column(name = "BankAccountNumber", length = 32)
    private String bankAccountNumber;

    @Column(name = "BankAccountName", length = 120)
    private String bankAccountName;

    /** referenceId duy nhất ta gửi PayOS — khóa idempotency phía PayOS. */
    @Column(name = "PayosReferenceId", length = 64)
    private String payosReferenceId;

    /** id lệnh chi PayOS trả về — dùng để poll trạng thái. */
    @Column(name = "PayosPayoutId", length = 64)
    private String payosPayoutId;

    /** Trạng thái giao dịch PayOS gần nhất (PROCESSING/SUCCEEDED/FAILED...). */
    @Column(name = "PayosTxnState", length = 32)
    private String payosTxnState;

    /** Hạn chót phải hoàn xong; quá hạn mà chưa SUCCEEDED ⇒ job cảnh báo admin. */
    @Column(name = "DeadlineAt")
    private LocalDateTime deadlineAt;

    @Builder.Default
    @Column(name = "AttemptCount", nullable = false)
    private int attemptCount = 0;

    @Column(name = "LastAttemptAt")
    private LocalDateTime lastAttemptAt;

    @Column(name = "FailureReason", length = 500)
    private String failureReason;

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "ProcessedAt")
    private LocalDateTime processedAt;
}