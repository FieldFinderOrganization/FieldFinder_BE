package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ví của shipper (shipper là một {@link User}): số dư duy nhất có dấu.
 * Dương = thu nhập ship rút được; âm = công nợ COD (tiền hàng thu hộ chưa nộp).
 * Số dư = tổng {@link ShipperWalletTransaction}. {@code negativeSince} đánh dấu lúc số dư bắt đầu âm
 * để tính hạn (grace) trước khi chặn nhận đơn mới.
 */
@Entity
@Table(
        name = "ShipperWallets",
        uniqueConstraints = @UniqueConstraint(name = "uk_shipper_wallet_user", columnNames = {"UserId"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipperWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "WalletId", updatable = false, nullable = false)
    private UUID walletId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private User shipper;

    @Builder.Default
    @Column(name = "Balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /** Thời điểm số dư bắt đầu âm; null khi số dư ≥ 0. Dùng để tính hạn (grace) trước khi chặn. */
    @Column(name = "NegativeSince")
    private LocalDateTime negativeSince;

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "UpdatedAt")
    private LocalDateTime updatedAt;
}
