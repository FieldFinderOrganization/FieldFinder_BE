package com.example.FieldFinder.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long productId;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    private String name;
    private String description;
    private Double price;

    private Integer stockQuantity; // Tổng số lượng trong kho (bao gồm cả hàng đang bị giữ)

    @Column(name = "locked_quantity", nullable = false)
    private Integer lockedQuantity = 0;

    private String imageUrl;
    private String brand;
    private LocalDateTime createdAt = LocalDateTime.now();
    private String sex;

    public int getAvailableQuantity() {
        return this.stockQuantity - this.lockedQuantity;
    }
}