package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ví của chủ sân: số dư duy nhất có dấu. Dương = tiền chủ sân (rút được, trừ reserve);
 * âm = nợ (overdraft, từ phạt hủy vượt số dư). Số dư = tổng {@link WalletTransaction}.
 * {@code negativeSince} đánh dấu lúc số dư bắt đầu âm để tính hạn chặn booking.
 */
@Entity
@Table(
        name = "ProviderWallets",
        uniqueConstraints = @UniqueConstraint(name = "uk_wallet_provider", columnNames = {"ProviderId"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "WalletId", updatable = false, nullable = false)
    private UUID walletId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ProviderId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Provider provider;

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
