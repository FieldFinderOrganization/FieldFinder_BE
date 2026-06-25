package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Một lệnh shipper NỘP LẠI tiền hàng thu hộ (COD) qua PayOS để xóa công nợ ví.
 * Cùng cơ chế với {@link WalletTopup} nhưng tiền vào ⇒ credit {@code COD_REMIT} cho ví shipper.
 *
 * <p>Vòng đời: PENDING (tạo link) → CREDITED (webhook báo PAID + xác nhận server-side qua PayOS
 * rồi giảm nợ ví). Khớp webhook theo {@code transactionId} (= PayOS paymentLinkId), duy nhất toàn cục.</p>
 */
@Entity
@Table(
        name = "ShipperCodRemits",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_remit_txn", columnNames = {"TransactionId"}),
                @UniqueConstraint(name = "uk_remit_ordercode", columnNames = {"OrderCode"})
        },
        indexes = @Index(name = "idx_remit_user", columnList = "UserId")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipperCodRemit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "RemitId", updatable = false, nullable = false)
    private UUID remitId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private User shipper;

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
