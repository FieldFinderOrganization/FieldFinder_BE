package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.ShipperWalletTxnType;
import com.example.FieldFinder.Enum.WalletTxnStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Một dòng sổ ví shipper (append-only) — dùng cho sao kê + idempotency.
 * amount CÓ DẤU: dương = cộng, âm = trừ. balanceAfter = số dư sau giao dịch.
 * Giao dịch WITHDRAWAL mang thêm các trường PayOS payout (giống ví chủ sân).
 */
@Entity
@Table(
        name = "ShipperWalletTransactions",
        indexes = {
                @Index(name = "idx_swtx_user", columnList = "UserId"),
                @Index(name = "idx_swtx_status", columnList = "Status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipperWalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "TxnId", updatable = false, nullable = false)
    private UUID txnId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private User shipper;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", nullable = false, length = 24)
    private ShipperWalletTxnType type;

    /** Có dấu: + cộng, − trừ. */
    @Column(name = "Amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "BalanceAfter", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "SourceType", length = 32)
    private String sourceType;   // ORDER | WITHDRAWAL | ADJUSTMENT

    @Column(name = "SourceId", length = 64)
    private String sourceId;     // orderId hoặc id nội bộ

    @Column(name = "Reason", length = 500)
    private String reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false, length = 16)
    private WalletTxnStatus status = WalletTxnStatus.COMPLETED;

    // ----- Chỉ dùng cho WITHDRAWAL (PayOS payout) -----
    @Column(name = "PayosReferenceId", length = 64)
    private String payosReferenceId;
    @Column(name = "PayosPayoutId", length = 64)
    private String payosPayoutId;
    @Column(name = "PayosTxnState", length = 32)
    private String payosTxnState;
    @Column(name = "BankBin", length = 12)
    private String bankBin;
    @Column(name = "BankAccountNumber", length = 32)
    private String bankAccountNumber;
    @Builder.Default
    @Column(name = "AttemptCount")
    private int attemptCount = 0;
    @Column(name = "LastAttemptAt")
    private LocalDateTime lastAttemptAt;
    @Column(name = "DeadlineAt")
    private LocalDateTime deadlineAt;
    @Column(name = "FailureReason", length = 500)
    private String failureReason;

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "ProcessedAt")
    private LocalDateTime processedAt;
}
