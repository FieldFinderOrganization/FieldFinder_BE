package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Một lệnh NẠP tiền vào ví chủ sân qua PayOS. Tách khỏi {@link Payment} (vốn gắn
 * customer/booking/order) vì nạp ví là luồng provider thuần.
 *
 * <p>Vòng đời: PENDING (tạo link) → CREDITED (webhook báo PAID + xác nhận server-side
 * qua PayOS rồi cộng ví). Khớp webhook theo {@code transactionId} (= PayOS paymentLinkId),
 * duy nhất toàn cục nên không lẫn luồng với thanh toán booking/order.</p>
 */
@Entity
@Table(
        name = "WalletTopups",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_topup_txn", columnNames = {"TransactionId"}),
                @UniqueConstraint(name = "uk_topup_ordercode", columnNames = {"OrderCode"})
        },
        indexes = @Index(name = "idx_topup_provider", columnList = "ProviderId")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTopup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "TopupId", updatable = false, nullable = false)
    private UUID topupId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ProviderId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Provider provider;

    @Column(name = "Amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** orderCode gửi PayOS (int dương, duy nhất). */
    @Column(name = "OrderCode", nullable = false)
    private long orderCode;

    /** PayOS paymentLinkId — khóa khớp webhook. */
    @Column(name = "TransactionId", length = 64)
    private String transactionId;

    @Column(name = "CheckoutUrl", length = 1000)
    private String checkoutUrl;

    @Column(name = "QrCode", length = 2000)
    private String qrCode;

    /** PENDING | CREDITED | FAILED. */
    @Builder.Default
    @Column(name = "Status", nullable = false, length = 16)
    private String status = "PENDING";

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "CreditedAt")
    private LocalDateTime creditedAt;
}
