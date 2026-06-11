package com.example.FieldFinder.entity;

import com.example.FieldFinder.Enum.PointTxType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ledger điểm thưởng: mỗi lần cộng/trừ là 1 dòng — minh bạch, dễ debug,
 * hoàn điểm idempotent qua cờ reverted trên dòng EARN.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "PointTransactions")
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserId", nullable = false)
    private User user;

    /** Signed: dương = cộng, âm = trừ. */
    @Column(name = "Amount", nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "Type", nullable = false, length = 20)
    private PointTxType type;

    @Column(name = "RefOrderId")
    private Long refOrderId;

    @Column(name = "RefDiscountId")
    private UUID refDiscountId;

    @Column(name = "Description")
    private String description;

    /** Chỉ dùng cho EARN_ORDER: đã bị revert thì không revert lần nữa. */
    @Column(name = "Reverted", nullable = false)
    @Builder.Default
    private boolean reverted = false;

    @Column(name = "CreatedAt")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
