package com.example.FieldFinder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;
import java.math.BigDecimal;

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

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_tags", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductVariant> variants;

    @Column(columnDefinition = "TEXT")
    private String embedding;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductDiscount> discounts = new ArrayList<>();


    @Transient
    private Integer onSalePercent;

    @Transient
    private Double salePrice;


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

    public int getTotalSold() {
        return variants == null ? 0 : variants.stream().mapToInt(ProductVariant::getSoldQuantity).sum();
    }
    public Double getSalePrice() {
        if (this.salePrice != null) {
            return this.salePrice;
        }
        return calculateDefaultSalePrice();
    }

    public Integer getOnSalePercent() {
        if (this.onSalePercent != null) {
            return this.onSalePercent;
        }

        if (this.price == null || this.price == 0) return 0;

        Double finalPrice = getSalePrice();
        if (finalPrice == null) return 0;

        double totalReduction = this.price - finalPrice;
        if (totalReduction <= 0) return 0;

        return (int) Math.round((totalReduction / this.price) * 100);
    }

    private Double calculateDefaultSalePrice() {
        if (this.price == null) return 0.0;
        double currentPrice = this.price;
        LocalDate now = LocalDate.now();

        if (this.discounts == null || this.discounts.isEmpty()) {
            return currentPrice;
        }

        for (ProductDiscount pd : this.discounts) {
            Discount d = pd.getDiscount();
            if (d != null && isValidDiscount(d, now)) {
                currentPrice = applyDiscountLogic(currentPrice, d);
            }
        }
        return Math.max(0, currentPrice);
    }

    private boolean isValidDiscount(Discount d, LocalDate now) {
        boolean isActive = d.getStatus() == Discount.DiscountStatus.ACTIVE;
        boolean isStarted = d.getStartDate() == null || !now.isBefore(d.getStartDate());
        boolean isNotExpired = d.getEndDate() == null || !now.isAfter(d.getEndDate());
        return isActive && isStarted && isNotExpired;
    }

    private double applyDiscountLogic(double currentPrice, Discount d) {
        double reduction = 0.0;
        BigDecimal valBd = d.getValue();
        double value = valBd != null ? valBd.doubleValue() : 0.0;

        if (d.getDiscountType() == Discount.DiscountType.FIXED_AMOUNT) {
            reduction = value;
        } else {
            reduction = currentPrice * (value / 100.0);
        }

        if (d.getMaxDiscountAmount() != null) {
            double maxLimit = d.getMaxDiscountAmount().doubleValue();
            if (maxLimit > 0) {
                reduction = Math.min(reduction, maxLimit);
            }
        }
        return currentPrice - reduction;
    }

    public Double getEffectivePrice() {
        return getSalePrice();
    }
}