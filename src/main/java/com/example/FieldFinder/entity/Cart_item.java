package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "Cart_item")
public class Cart_item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id", updatable = false, nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CartId", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ProductId", nullable = false)
    private Product product;

    @Column(name = "Quantity", nullable = false)
    private int quantity;

    @Column(name = "PriceAtTime", nullable = false)
    private Double priceAtTime; // Giá sau khi giảm (Final Price)

    // --- THÊM TRƯỜNG NÀY ---
    @Column(name = "OriginalPrice")
    private Double originalPrice; // Giá gốc niêm yết tại thời điểm thêm vào giỏ
    // -----------------------

    @Column(name = "Size", nullable = false)
    private String size;

}