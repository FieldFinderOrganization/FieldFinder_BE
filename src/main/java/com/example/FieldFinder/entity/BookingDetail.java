package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "booking_detail")
public class BookingDetail implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BookingDetailId")
    private Long bookingDetailId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "BookingId", nullable = false)
    private Booking booking;

    // Liên kết với Sân (Có thể null nếu đây là đơn hàng mua sản phẩm)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PitchId", nullable = true)
    private Pitch pitch;

    // Liên kết với Sản phẩm (Có thể null nếu là đặt sân)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ProductId", nullable = true)
    private Product product;

    // Số lượng (Dùng cho sản phẩm)
    @Column(name = "Quantity")
    private Integer quantity;

    @Column(name = "Price")
    private Double price;

    private int slot;
    private String name;

    private BigDecimal priceDetail;
}
