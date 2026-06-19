package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.ProviderDebtStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Khoản chủ sân NỢ hệ thống: khi chủ sân hủy đơn, hệ thống ứng tiền hoàn (110%) cho khách
 * từ TK chi chung. Số tiền này ghi nợ chủ sân để trừ vào doanh thu kỳ sau.
 * Một booking chỉ sinh một khoản nợ (idempotent qua unique sourceBookingId).
 */
@Entity
@Table(
        name = "ProviderDebts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_provider_debt_booking",
                columnNames = {"SourceBookingId"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderDebt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ProviderDebtId", updatable = false, nullable = false)
    private UUID providerDebtId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ProviderId", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Provider provider;

    @Column(name = "SourceBookingId", nullable = false, length = 64)
    private String sourceBookingId;

    /** Số tiền hệ thống đã ứng (110% giá booking). */
    @Column(name = "Amount", nullable = false)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false, length = 16)
    private ProviderDebtStatus status = ProviderDebtStatus.OUTSTANDING;

    @Column(name = "Reason", length = 500)
    private String reason;

    /** Hạn chót chủ sân phải trả; quá hạn còn OUTSTANDING ⇒ chặn nhận booking mới. */
    @Column(name = "DeadlineAt")
    private LocalDateTime deadlineAt;

    @Builder.Default
    @Column(name = "CreatedAt", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "SettledAt")
    private LocalDateTime settledAt;
}
