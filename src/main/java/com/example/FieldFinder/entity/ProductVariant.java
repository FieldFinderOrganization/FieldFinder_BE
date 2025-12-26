package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "product_variants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String size;

    @Column(nullable = false)
    private Integer stockQuantity;

    @Column(nullable = false)
    private Integer lockedQuantity = 0;

    @Column(nullable = false)
    private Integer soldQuantity = 0;

    public int getAvailableQuantity() {
        return this.stockQuantity - this.lockedQuantity;
    }

    public Integer getQuantity() {
        return this.stockQuantity;
    }
}