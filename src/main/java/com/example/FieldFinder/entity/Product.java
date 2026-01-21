package com.example.FieldFinder.entity;

import com.example.FieldFinder.converter.StringSetConverter;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;
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

    @Convert(converter = StringSetConverter.class)
    @Column(name = "tags", columnDefinition = "TEXT")
    @Builder.Default
    private Set<String> tags = new HashSet<>();

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
        return this.price;
    }

    public Integer getOnSalePercent() {
        if (this.onSalePercent != null) {
            return this.onSalePercent;
        }

        if (this.price == null || this.price == 0) return 0;

        Double finalPrice = getSalePrice();
        if (finalPrice == null || finalPrice.equals(this.price)) return 0;

        double totalReduction = this.price - finalPrice;
        if (totalReduction <= 0) return 0;

        return (int) Math.round((totalReduction / this.price) * 100);
    }

    public void calculateSalePriceForUser(List<Discount> availableDiscounts) {
        if (this.price == null) {
            this.salePrice = 0.0;
            return;
        }

        double currentPrice = this.price;
        LocalDate now = LocalDate.now();

        if (availableDiscounts != null && !availableDiscounts.isEmpty()) {
            for (Discount d : availableDiscounts) {
                if (d != null && isValidDiscount(d, now)) {
                    currentPrice = applyDiscountLogic(currentPrice, d);
                }
            }
        }

        this.salePrice = Math.max(0, currentPrice);

        if (this.price > 0) {
            double totalReduction = this.price - this.salePrice;
            this.onSalePercent = totalReduction <= 0 ? 0 : (int) Math.round((totalReduction / this.price) * 100);
        } else {
            this.onSalePercent = 0;
        }
    }

    private boolean isValidDiscount(Discount d, LocalDate now) {
        boolean isActive = d.getStatus() == Discount.DiscountStatus.ACTIVE;
        boolean isStarted = d.getStartDate() == null || !now.isBefore(d.getStartDate());
        boolean isNotExpired = d.getEndDate() == null || !now.isAfter(d.getEndDate());
        boolean isStockAvailable = d.getQuantity() > 0;
        return isActive && isStarted && isNotExpired && isStockAvailable;
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