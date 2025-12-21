package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;

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
    private String imageUrl;
    private String brand;
    private String sex;
    private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_tags", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag")
    private List<String> tags = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductVariant> variants;

    @Column(columnDefinition = "TEXT")
    private String embedding;

    public double[] getEmbeddingArray() {
        if (embedding == null || embedding.isEmpty()) return new double[0];
        try {
            String clean = embedding.replace("[", "").replace("]", "");
            return Arrays.stream(clean.split(","))
                    .mapToDouble(Double::parseDouble)
                    .toArray();
        } catch (Exception e) {
            return new double[0];
        }
    }

    public int getTotalStock() {
        return variants == null ? 0 : variants.stream().mapToInt(ProductVariant::getStockQuantity).sum();
    }

    public int getTotalSold() {
        return variants == null ? 0 : variants.stream().mapToInt(ProductVariant::getSoldQuantity).sum();
    }
    @Transient
    private Integer onSalePercent;

    @Transient
    private Double salePrice;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductDiscount> discounts = new ArrayList<>();

    public Double getEffectivePrice() {
        if (this.discounts == null || this.discounts.isEmpty()) {
            return this.price;
        }

        int maxPercent = 0;
        LocalDate now = LocalDate.now();

        for (ProductDiscount pd : this.discounts) {
            Discount d = pd.getDiscount();

            if (d == null) continue;

            boolean isActive = d.getStatus() == Discount.DiscountStatus.ACTIVE;
            boolean isStarted = !now.isBefore(d.getStartDate());
            boolean isNotExpired = !now.isAfter(d.getEndDate());

            if (isActive && isStarted && isNotExpired) {
                if (d.getPercentage() > maxPercent) {
                    maxPercent = d.getPercentage();
                }
            }
        }

        if (maxPercent > 0) {
            double discountAmount = this.price * (maxPercent / 100.0);
            return this.price - discountAmount;
        }

        return this.price;
    }
}