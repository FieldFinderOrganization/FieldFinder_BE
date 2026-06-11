package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ghi nhận 1 lượt sử dụng voucher cho 1 đơn hàng/đặt sân — để hoàn lại khi hủy.
 * REFUND_CREDIT có thể dùng nhiều đơn (residual) nên cần bảng riêng thay vì cột trên UserDiscount.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "DiscountUsages")
public class DiscountUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UserDiscountId", nullable = false)
    private UserDiscount userDiscount;

    @Column(name = "OrderId")
    private Long orderId;

    @Column(name = "BookingId")
    private UUID bookingId;

    /** Số tiền đã trừ trong lượt dùng này — bắt buộc với REFUND_CREDIT để hoàn đúng số dư. */
    @Column(name = "AmountDeducted")
    private BigDecimal amountDeducted;

    /** Idempotency: đã hoàn rồi thì không hoàn lại lần nữa. */
    @Column(name = "Reverted", nullable = false)
    @Builder.Default
    private boolean reverted = false;

    @Column(name = "CreatedAt")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
